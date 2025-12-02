# bolt/modules/onboard/plans/onboard.pp
# Bolt plan to onboard developers: installs tools, initializes submodules and verifies a smoke build
plan onboard::onboard(Boolean $yes = false, Optional[String] $sdk_path = undef, Boolean $install_android_studio = false, Boolean $dry_run = false) {
  # Build a timestamp by asking the local shell date (avoids deprecated strftime signatures)
  # Run date as a single command string (avoid Bolt treating arguments as targets)
  $date_res = run_command('date +%Y%m%d-%H%M%S', 'localhost')
  # Bolt returns a ResultSet; access the first result by index 0
  if $date_res[0] {
    $timestamp = $date_res[0]['stdout'].strip
  } else {
    fail('Failed to obtain timestamp from local date command')
  }

  $logdir = '/tmp/bolt_onboard_logs'
  run_command("mkdir -p ${logdir}", 'localhost')
  notice("Starting module onboarding plan (logs -> ${logdir}/onboard-${timestamp}.log)")

  # Detect OS family: prefer uname -s; if uname is unavailable assume Windows
  $uname_res = run_command('uname -s', 'localhost')
  if $uname_res[0] and $uname_res[0]['exit_code'] == 0 {
    $uname = $uname_res[0]['stdout'].strip
    # Treat macOS and Linux both as 'unix' (run bash tasks)
    if $uname =~ /Darwin|Linux|FreeBSD|Unix/ {
      $os_family = 'unix'
    } else {
      $os_family = 'unix'
    }
  } else {
    # uname failed; assume Windows target
    $os_family = 'windows'
  }

  notice("Detected OS family: ${os_family}")

  # Prepare environment mapping
  $base_env = {
    'YES'                   => $yes ? { true => '1', default => '0' },
    'INSTALL_ANDROID_STUDIO' => $install_android_studio ? { true => '1', default => '0' },
  }

  # Add SDK_PATH if provided (using + operator to avoid deprecation warning)
  $env_with_sdk = if $sdk_path != undef {
    $base_env + { 'SDK_PATH' => $sdk_path }
  } else {
    $base_env
  }

  if $os_family == 'unix' {
    notice('Detected Unix-like OS: running bash task scripts')

    # Install Java 21
    notice('Running: install_java.sh')
    if $dry_run {
      notice('DRY_RUN: would execute install_java.sh')
    } else {
      run_command('bash bolt/tasks/install_java.sh', 'localhost', { 'environment' => $env_with_sdk })
    }

    # After Java install, attempt to locate JAVA_HOME for Java 17+ and add it to the environment
    $discovered_java_home = if $dry_run {
      # In dry_run, we don't discover Java, so use a placeholder (won't be used)
      ''
    } else {
      # Prefer persisted JAVA_HOME if installer wrote it to bolt/.java_home
      $persist_res = run_command('bash -lc "cat bolt/.java_home 2>/dev/null || true"', 'localhost')
      if $persist_res[0] and $persist_res[0]['stdout'].strip != '' {
        $java_home = $persist_res[0]['stdout'].strip
        notice("Using persisted JAVA_HOME from bolt/.java_home: ${java_home}")
      } else {
        # Try /usr/libexec/java_home (macOS)
        $jh_res = run_command('/usr/libexec/java_home -v 21 2>/dev/null || true', 'localhost')
        if $jh_res[0] and $jh_res[0]['stdout'].strip != '' {
          $java_home = $jh_res[0]['stdout'].strip
        } else {
          # Try Homebrew prefix for openjdk@21
          $brew_prefix_res = run_command('bash -lc "brew --prefix openjdk@21 2>/dev/null || true"', 'localhost')
          if $brew_prefix_res[0] and $brew_prefix_res[0]['stdout'].strip != '' {
            $bp = $brew_prefix_res[0]['stdout'].strip
            $java_home = "${bp}/libexec/openjdk.jdk/Contents/Home"
          } else {
            # Try to derive from `command -v java`
            $java_bin_res = run_command('bash -lc "command -v java 2>/dev/null || true"', 'localhost')
            if $java_bin_res[0] and $java_bin_res[0]['stdout'].strip != '' {
              $java_bin = $java_bin_res[0]['stdout'].strip
              $rl_res = run_command( 'bash -lc "readlink -f \"${java_bin}\" 2>/dev/null || true"', 'localhost')
              if $rl_res[0] and $rl_res[0]['stdout'].strip != '' {
                $rl = $rl_res[0]['stdout'].strip
                # strip trailing /bin/java
                $java_home = regsubst($rl, '/bin/java$', '', '')
              }
            }
          }
        }
      }

      # Verify JAVA_HOME points to a JDK >=17 by running $JAVA_HOME/bin/java -version
      if $java_home and $java_home != '' {
        # Ensure the discovered java binary exists
        $check_cmd = "bash -lc 'if [ -x \"${java_home}/bin/java\" ]; then echo OK; else echo MISSING; fi'"
        notice("Checking JAVA_HOME java binary with: ${check_cmd}")
        $check_res = run_command($check_cmd, 'localhost')
        $check_out = if $check_res[0] and $check_res[0]['stdout'].strip != '' {
          $check_res[0]['stdout'].strip
        } else {
          ''
        }

        # If discovered java_home doesn't have valid java binary, try to find a candidate
        $java_home_candidate = if $check_out != 'OK' {
          notice("Discovered JAVA_HOME java binary missing or not executable: ${java_home}/bin/java")
          # Try to detect an installed JDK under /Library/Java/JavaVirtualMachines
          $ls_res = run_command("bash -lc 'ls -1 /Library/Java/JavaVirtualMachines 2>/dev/null || true'", 'localhost')
          if $ls_res[0] and $ls_res[0]['stdout'].strip != '' {
            $first = split($ls_res[0]['stdout'].strip, '\n')[0]
            if $first != '' {
              $candidate = "/Library/Java/JavaVirtualMachines/${first}/Contents/Home"
              notice("Found candidate JDK at ${candidate}")
              $candidate
            } else {
              $java_home
            }
          } else {
            $java_home
          }
        } else {
          $java_home
        }
        
        # Use candidate if found, otherwise use original
        $final_java_home = $java_home_candidate

        # Run java -version using the chosen JAVA_HOME and capture output (stderr redirected)
        $verify_cmd = "bash -lc '${final_java_home}/bin/java -version 2>&1 | head -n1'"
        notice("Verifying Java using: ${verify_cmd}")
        $verify_res = run_command($verify_cmd, 'localhost')
        $verify_out = if $verify_res[0] and $verify_res[0]['stdout'].strip != '' {
          $verify_res[0]['stdout'].strip
        } else {
          ''
        }

        # Fallback to system java if direct call produced no output
        $final_verify_out = if $verify_out == '' {
          notice('Direct JAVA_HOME java binary produced no output; falling back to system java')
          $fallback_res = run_command('bash -lc "java -version 2>&1 | head -n1"', 'localhost')
          if $fallback_res[0] and $fallback_res[0]['stdout'].strip != '' {
            $fallback_res[0]['stdout'].strip
          } else {
            ''
          }
        } else {
          $verify_out
        }

        notice("Java verification output: '${final_verify_out}'")
        if $final_verify_out =~ /([0-9]+)(?:\.[0-9]+)*/ {
          $maj = Integer($1)
          if $maj < 17 {
            fail("Detected Java major version ${maj}; sdkmanager requires JDK 17+. Please install JDK 17+ (e.g., Temurin 17 or 21) and re-run the plan.")
          }
        } else {
          fail("Could not verify Java version from discovered JAVA_HOME. Output was: '${final_verify_out}'. Please ensure JDK 17+ is installed and accessible.")
        }

        # Return final_java_home for use in outer scope
        $final_java_home
      } else {
        fail('JDK 17+ not found after install_java. Please install JDK 17+ (Temurin or OpenJDK) and re-run the plan.')
      }
    }
    
    # Build final env: add JAVA_HOME and PATH if Java was discovered (not in dry_run)
    $env = if $dry_run {
      $env_with_sdk
    } else {
      # Java discovery succeeded (or we would have failed), so merge in JAVA_HOME
      $path_res = run_command('bash -lc "echo $PATH"', 'localhost')
      $current_path = if $path_res[0] and $path_res[0]['stdout'].strip != '' {
        $path_res[0]['stdout'].strip
      } else {
        ''
      }
      # Assign the result of merge to $env (using + operator to avoid deprecation warning)
      $env_with_sdk + { 'JAVA_HOME' => $discovered_java_home, 'PATH' => "${discovered_java_home}/bin:${current_path}" }
    }
    
    if !$dry_run {
      notice("Final JAVA_HOME=${discovered_java_home}")
      if 'JAVA_HOME' in $env {
        notice("Environment JAVA_HOME=${env['JAVA_HOME']}")
      } else {
        notice("WARNING: JAVA_HOME not in env hash!")
      }
      if 'PATH' in $env {
        notice("Environment PATH=${env['PATH']}")
      } else {
        notice("WARNING: PATH not in env hash!")
      }
      notice("Full env keys: ${keys($env)}")
    }

    # Install Android CLI + NDK
    notice('Running: install_android_cli_ndk.sh')
    if $dry_run {
      notice('DRY_RUN: would execute install_android_cli_ndk.sh')
    } else {
      # Debug: verify environment before passing
      notice("About to run install_android_cli_ndk.sh with env keys: ${keys($env)}")
      if 'JAVA_HOME' in $env {
        notice("JAVA_HOME in env: ${env['JAVA_HOME']}")
      }
      # Pass environment explicitly
      run_command('bash bolt/tasks/install_android_cli_ndk.sh', 'localhost', { 'environment' => $env })
    }

    # Android Studio prompt: only if requested
    if $install_android_studio {
      notice('Running: prompt_android_studio.sh')
      if $dry_run {
        notice('DRY_RUN: would prompt/install Android Studio GUI')
      } else {
        run_command('bash bolt/tasks/prompt_android_studio.sh', 'localhost', { 'environment' => $env })
      }
    } else {
      notice('Android Studio GUI install skipped by parameter')
    }

    # Detect SDK and write local.properties
    notice('Running: create_local_properties.sh')
    if $dry_run {
      notice('DRY_RUN: would create local.properties with detected or provided SDK path')
    } else {
      run_command('bash bolt/tasks/create_local_properties.sh', 'localhost', { 'environment' => $env })
    }

    # Init SSH-only submodules
    notice('Running: init_submodules.sh')
    if $dry_run {
      notice('DRY_RUN: would initialize SSH-only submodules (requires SSH keys)')
    } else {
      run_command('bash bolt/tasks/init_submodules.sh', 'localhost', { 'environment' => $env })
    }

    # Verify build with a smoke assemble
    notice('Running: verify_build.sh')
    if $dry_run {
      notice('DRY_RUN: would run a smoke Gradle assemble to verify the toolchain')
    } else {
      run_command('bash bolt/tasks/verify_build.sh', 'localhost', { 'environment' => $env })
    }

  } elsif $os_family == 'windows' {
     notice('Detected Windows: running PowerShell task scripts')

    # Check elevation: instruct-only if not elevated
    $elev_check = run_command('powershell -NoProfile -NonInteractive -Command "if (([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { Write-Output \"elevated\" } else { Write-Output \"not_elevated\" }"', 'localhost')
    if $elev_check[0] {
      if $elev_check[0]['stdout'].strip == 'not_elevated' {
        fail('This plan requires elevated privileges on Windows for package installation steps. Please re-run the Bolt plan in an elevated PowerShell (Run as Administrator).')
      }
    } else {
      fail('Failed to determine Windows elevation state')
    }

    notice('Running: install_java.ps1')
    if $dry_run {
      notice('DRY_RUN: would execute install_java.ps1')
    } else {
      run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/install_java.ps1', 'localhost', { 'environment' => $env })
    }
    notice('Running: install_android_cli_ndk.ps1')
    if $dry_run {
      notice('DRY_RUN: would execute install_android_cli_ndk.ps1')
    } else {
      run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/install_android_cli_ndk.ps1', 'localhost', { 'environment' => $env })
    }

    if $install_android_studio {
      notice('Running: prompt_android_studio.ps1')
      if $dry_run {
        notice('DRY_RUN: would prompt/install Android Studio GUI (PowerShell)')
      } else {
        run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/prompt_android_studio.ps1', 'localhost', { 'environment' => $env })
      }
    } else {
      notice('Android Studio GUI install skipped by parameter')
    }

    notice('Running: create_local_properties.ps1')
    if $dry_run {
      notice('DRY_RUN: would create local.properties (PowerShell)')
    } else {
      run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/create_local_properties.ps1', 'localhost', { 'environment' => $env })
    }
    notice('Running: init_submodules.ps1')
    if $dry_run {
      notice('DRY_RUN: would initialize SSH-only submodules (PowerShell)')
    } else {
      run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/init_submodules.ps1', 'localhost', { 'environment' => $env })
    }
    notice('Running: verify_build.ps1')
    if $dry_run {
      notice('DRY_RUN: would run verify_build.ps1 (PowerShell)')
    } else {
      run_command('powershell -NoProfile -ExecutionPolicy Bypass -File bolt/tasks/verify_build.ps1', 'localhost', { 'environment' => $env })
    }

  } else {
    fail("Unsupported OS family: ${os_family}. This plan supports macOS (Darwin) and Windows.")
  }

  notice('Module onboarding plan completed')
}
