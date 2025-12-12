#!/usr/bin/env bash
# bolt/tasks/init_submodules.sh
# Initialize git submodules using SSH. Do not modify submodule URLs. Detect SSH key presence and instruct if missing.
set -euo pipefail

LOGDIR="bolt/logs"
mkdir -p "$LOGDIR"
TS=$(date +%Y%m%d-%H%M%S)
LOG="$LOGDIR/init_submodules-$TS.log"
exec > >(tee -a "$LOG") 2>&1

echo "[init_submodules] Starting"
PROJECT_ROOT="$(pwd)"

# Ensure we're in a git repo
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Not inside a git repository. Please run this script from the repository root."
  exit 1
fi

# Check .gitmodules
if [[ ! -f ".gitmodules" ]]; then
  echo ".gitmodules not found. No submodules to init."
  exit 0
fi

# Check for SSH agent and keys
SSH_OK=0
if [[ -n "${SSH_AUTH_SOCK:-}" ]]; then
  echo "SSH agent detected via SSH_AUTH_SOCK"
  if ssh-add -l >/dev/null 2>&1; then
    echo "SSH agent has identities"
    SSH_OK=1
  else
    echo "SSH agent has no identities"
  fi
else
  echo "No SSH_AUTH_SOCK detected. Agent may not be running."
fi

# Check for local SSH keys
if [[ $SSH_OK -eq 0 ]]; then
  if [[ -f "$HOME/.ssh/id_ed25519" || -f "$HOME/.ssh/id_rsa" || -f "$HOME/.ssh/id_ecdsa" ]]; then
    echo "Found local SSH keys in ~/.ssh"
    echo "Please ensure your private key is added to the SSH agent (ssh-add ~/.ssh/id_rsa)",
    echo "and that your public key is uploaded to GitHub (https://github.com/settings/keys)"
  else
    echo "No SSH keys found in ~/.ssh. Please add an existing SSH key and upload the public key to GitHub: https://github.com/settings/keys"
  fi
  echo "Attempting an SSH connection test to git@github.com"
  SSH_TEST_OUTPUT=$(ssh -T git@github.com 2>&1 || true)
  if echo "$SSH_TEST_OUTPUT" | grep -qiE '(successfully authenticated|Hi .*! You.*ve successfully authenticated)'; then
    echo "SSH to github.com succeeded"
    echo "$SSH_TEST_OUTPUT" | head -n1
    SSH_OK=1
  elif echo "$SSH_TEST_OUTPUT" | grep -qiE '(Permission denied|Host key verification failed)'; then
    echo "SSH authentication to GitHub failed. Aborting submodule init. Follow these steps to fix:"
    echo "  1) Ensure you have an SSH key (see https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-ssh-keys)"
    echo "  2) Add your private key to the agent: ssh-add ~/.ssh/id_rsa"
    echo "  3) Verify with: ssh -T git@github.com"
    echo "  4) Once SSH access works, re-run this plan to initialize submodules"
    echo "SSH test output: $SSH_TEST_OUTPUT"
    exit 1
  else
    # If we get here, SSH might have worked but with unexpected output
    # Check exit code more carefully - GitHub returns exit code 1 even on success
    # The key is whether we got "successfully authenticated" in the output
    if echo "$SSH_TEST_OUTPUT" | grep -qi 'successfully'; then
      echo "SSH to github.com succeeded (unexpected output format)"
      echo "$SSH_TEST_OUTPUT" | head -n1
      SSH_OK=1
    else
      echo "SSH authentication to GitHub failed. Aborting submodule init. Follow these steps to fix:"
      echo "  1) Ensure you have an SSH key (see https://docs.github.com/en/authentication/connecting-to-github-with-ssh/managing-ssh-keys)"
      echo "  2) Add your private key to the agent: ssh-add ~/.ssh/id_rsa"
      echo "  3) Verify with: ssh -T git@github.com"
      echo "  4) Once SSH access works, re-run this plan to initialize submodules"
      echo "SSH test output: $SSH_TEST_OUTPUT"
      exit 1
    fi
  fi
fi

# At this point SSH access should be available
echo "Initializing submodules (SSH-only)"
# Ensure submodules configured with SSH URLs (fail if any use HTTPS)
if grep -E "url *= *https://" .gitmodules >/dev/null 2>&1; then
  echo "Detected HTTPS submodule URLs in .gitmodules. This plan enforces SSH-only submodules. Please convert URLs to SSH in .gitmodules and re-run."
  exit 1
fi

DRY_RUN=${DRY_RUN:-0}
if [[ "$DRY_RUN" == "1" ]]; then
  echo "DRY_RUN=1: Skipping actual git submodule update. (Would run: git submodule sync --recursive && git submodule update --init --recursive)"
else
  # Sync and update
  git submodule sync --recursive
  git submodule update --init --recursive
fi

echo "Submodules initialized successfully"

echo "[init_submodules] Completed"
exit 0
