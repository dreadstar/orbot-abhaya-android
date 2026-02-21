# Claude Internal Tools Reference

**Last Updated:** February 16, 2026

This document catalogs all internal tools available to Claude agents working in VS Code, organized by category. These tools should be preferred over terminal commands whenever possible (see AGENTS.md Rule #0).

---

## 📁 FILE SYSTEM OPERATIONS

### `create_file`
**Purpose:** Create new files with specified content  
**Usage:** Creating any new file (code, documentation, configuration)  
**Parameters:**
- `filePath` (required): Absolute path to the file
- `content` (required): Complete file content

**When to use:** ALWAYS for creating files - never use `cat > file`, `echo >`, or heredocs

### `create_directory`
**Purpose:** Recursively create directory structure (like `mkdir -p`)  
**Usage:** Creating nested directories  
**Parameters:**
- `dirPath` (required): Absolute path to directory

**Note:** Not needed before `create_file` - that tool auto-creates directories

### `read_file`
**Purpose:** Read file contents with specific line ranges  
**Usage:** Reading code, logs, configuration files  
**Parameters:**
- `filePath` (required): Absolute path to file
- `startLine` (required): Starting line number (1-indexed)
- `endLine` (required): Ending line number (inclusive)

**When to use:** ALWAYS for reading files - avoid `cat`, `head`, `tail`, `sed -n`

**Best practice:** Read larger ranges over multiple small reads; can read multiple ranges in parallel

### `replace_string_in_file`
**Purpose:** Edit existing file by replacing exact text string  
**Usage:** Making single edits to code/config files  
**Parameters:**
- `filePath` (required): Absolute path to file
- `oldString` (required): Exact text to replace (include 3-5 lines context before/after)
- `newString` (required): Replacement text (exact, including whitespace)

**When to use:** ALWAYS for file edits - avoid `sed -i`, `awk`, `perl`

**Critical:** Include sufficient context (3-5 lines before/after target) to ensure unique match

### `multi_replace_string_in_file`
**Purpose:** Apply multiple replace operations in one efficient call  
**Usage:** Making multiple independent edits across files  
**Parameters:**
- `explanation` (required): Brief description of changes
- `replacements` (required): Array of replacement operations (each with filePath, oldString, newString, explanation)

**When to use:** Whenever making 2+ independent edits - much more efficient than sequential calls

### `list_dir`
**Purpose:** List contents of a directory  
**Usage:** Exploring directory structure  
**Parameters:**
- `path` (required): Absolute path to directory

**Returns:** File/folder names (folders end with `/`)

---

## 🔍 SEARCH & DISCOVERY

### `file_search`
**Purpose:** Find files matching glob patterns  
**Usage:** Locating files by name/path pattern  
**Parameters:**
- `query` (required): Glob pattern (e.g., `**/*.kt`, `src/**`)
- `maxResults` (optional): Limit results

**Example:** `**/*.{js,ts}` finds all JS/TS files

### `grep_search`
**Purpose:** Fast text search across workspace (exact string or regex)  
**Usage:** Finding code, logs, specific text  
**Parameters:**
- `query` (required): Search pattern (string or regex)
- `isRegexp` (required): Boolean - is pattern a regex?
- `includePattern` (optional): Limit search to files matching glob
- `includeIgnoredFiles` (optional): Search in gitignored files
- `maxResults` (optional): Limit results

**When to use:** Known exact text or regex patterns - prefer over `grep`, `find`, `ag`, `rg`

**Best practice:** Use regex alternation `word1|word2|word3` to search multiple terms at once

### `semantic_search`
**Purpose:** Natural language search for relevant code/comments  
**Usage:** Finding code when you don't know exact text  
**Parameters:**
- `query` (required): Natural language description

**When to use:** When `grep_search` isn't precise enough; exploring unfamiliar codebase

**Note:** Don't call in parallel with other searches

### `list_code_usages`
**Purpose:** Find all usages/references/definitions of symbols  
**Usage:** Finding where classes/methods/variables are used  
**Parameters:**
- `symbolName` (required): Name of symbol (class, function, variable)
- `filePaths` (optional): Files likely containing definition

**Use cases:**
- Finding sample implementations of interfaces
- Checking function usage across codebase
- Updating all usages when changing signatures

---

## 📝 CODE EDITING TOOLS

### `get_errors`
**Purpose:** Get compile/lint errors in files  
**Usage:** Validating code after edits, debugging issues  
**Parameters:**
- `filePaths` (optional): Specific files to check (omit for all errors)

**When to use:** After editing files, when user mentions errors/problems

### `get_changed_files`
**Purpose:** Get git diffs of current file changes  
**Usage:** Reviewing uncommitted changes  
**Parameters:**
- `repositoryPath` (optional): Path to git repo
- `sourceControlState` (optional): Filter by staged/unstaged/merge-conflicts

---

## 📓 NOTEBOOK OPERATIONS

### `create_new_jupyter_notebook`
**Purpose:** Generate new Jupyter notebooks  
**Usage:** Creating interactive data analysis notebooks  
**Parameters:**
- `query` (required): Description of desired notebook

**When to use:** Only when user explicitly requests notebook or context suggests it

### `copilot_getNotebookSummary`
**Purpose:** Get notebook metadata (cells, IDs, execution info)  
**Usage:** Understanding notebook structure before editing  
**Parameters:**
- `filePath` (required): Path to .ipynb file

**Returns:** Cell IDs, types, languages, execution details, output types

### `edit_notebook_file`
**Purpose:** Edit notebook cells (insert/delete/modify)  
**Usage:** Adding or changing notebook cells  
**Parameters:**
- `filePath` (required): Path to notebook
- `editType` (required): `insert`, `delete`, or `edit`
- `cellId` (required): Target cell ID or `TOP`/`BOTTOM`
- `language` (optional): Cell language (python, markdown, etc.)
- `newCode` (optional): Cell content

### `run_notebook_cell`
**Purpose:** Execute notebook code cell  
**Usage:** Running cells after creation/editing  
**Parameters:**
- `filePath` (required): Path to notebook
- `cellId` (required): Code cell ID to execute
- `continueOnError` (optional): Continue if error occurs
- `reason` (optional): Explanation why running

**Important:** Don't execute Markdown cells; avoid referencing cell IDs in user messages

### `configure_notebook`
**Purpose:** Configure notebook kernel before first use  
**Usage:** Setting up notebook environment  
**Parameters:**
- `filePath` (required): Path to notebook

**When to use:** ALWAYS before first cell execution or package operations

### `notebook_install_packages`
**Purpose:** Install packages in notebook kernel  
**Usage:** Adding Python packages for notebook use  
**Parameters:**
- `filePath` (required): Path to notebook
- `packageList` (required): Array of package names

**Prerequisite:** Call `configure_notebook` first

### `notebook_list_packages`
**Purpose:** List packages in notebook kernel  
**Usage:** Checking available packages  
**Parameters:**
- `filePath` (required): Path to notebook

**Prerequisite:** Call `configure_notebook` first

---

## 🐍 PYTHON ENVIRONMENT TOOLS

### `configure_python_environment`
**Purpose:** Set up Python environment for workspace/file  
**Usage:** Initializing Python environment before any Python operations  
**Parameters:**
- `resourcePath` (optional): Path to file/workspace

**When to use:** ALWAYS before Python tools or running Python commands

### `get_python_environment_details`
**Purpose:** Get environment type, Python version, installed packages  
**Usage:** Understanding current Python setup  
**Parameters:**
- `resourcePath` (optional): Path to file/workspace

**Prerequisite:** Call `configure_python_environment` first

### `get_python_executable_details`
**Purpose:** Get Python executable path and command details  
**Usage:** Building correct Python commands for terminal  
**Parameters:**
- `resourcePath` (optional): Path to file/workspace

**When to use:** ALWAYS before executing Python in terminal - don't assume `python` works

**Prerequisite:** Call `configure_python_environment` first

### `install_python_packages`
**Purpose:** Install Python packages in environment  
**Usage:** Adding dependencies to project  
**Parameters:**
- `packageList` (required): Array of package names
- `resourcePath` (optional): Path to file/workspace

**Prerequisite:** Call `configure_python_environment` first

---

## 🔧 PROJECT & WORKSPACE SETUP

### `create_new_workspace`
**Purpose:** Generate complete project scaffolding  
**Usage:** Creating new full projects (TypeScript, React, MCP servers, etc.)  
**Parameters:**
- `query` (required): Description of desired project

**When to use:**
- User wants "new project", "create workspace", "set up [framework]"
- Full project initialization with dependencies, configs, folder structure

**When NOT to use:**
- Creating single files
- Adding files to existing projects
- Simple code examples

### `get_project_setup_info`
**Purpose:** Get setup instructions for specific project types  
**Usage:** After calling `create_new_workspace`  
**Parameters:**
- `projectType` (required): `python-script`, `python-project`, `mcp-server`, `vscode-extension`, `next-js`, `vite`, `other`

**Note:** Only call AFTER `create_new_workspace`

---

## 🌐 WEB & API TOOLS

### `fetch_webpage`
**Purpose:** Get main content from web pages  
**Usage:** Summarizing/analyzing web content  
**Parameters:**
- `urls` (required): Array of URLs
- `query` (required): What to search for in content

### `open_simple_browser`
**Purpose:** Preview websites in VS Code browser  
**Usage:** Viewing locally hosted sites, demos  
**Parameters:**
- `url` (required): HTTP/HTTPS URL to open

### `github_repo`
**Purpose:** Search GitHub repositories for code snippets  
**Usage:** Finding code examples from specific repos  
**Parameters:**
- `repo` (required): Format `owner/repo`
- `query` (required): Search query

**When to use:** User asks for code from specific GitHub repo (not repos in workspace)

### `get_vscode_api`
**Purpose:** Get VS Code extension API documentation  
**Usage:** Building VS Code extensions  
**Parameters:**
- `query` (required): API/interface/concept to document

**When to use:** Extension development questions - specific APIs, contribution points, proposed APIs

**When NOT to use:** General programming, creating standalone files, using VS Code as editor

---

## 🔨 BUILD & EXECUTION

### `run_in_terminal`
**Purpose:** Execute shell commands in persistent bash session  
**Usage:** Build, test, deploy, git, system commands  
**Parameters:**
- `command` (required): Shell command to run
- `explanation` (required): One-sentence description
- `isBackground` (required): Boolean - runs in background?

**When to use:**
- Build commands (Gradle, Maven, make)
- Deployment (ADB, device interaction)
- Git operations
- System commands
- Complex pipelines where internal tools insufficient

**When NOT to use:**
- File creation/reading/editing (use internal tools)
- Anything that can be done with internal tools

**Best practices:**
- Use absolute paths
- Chain with `&&`, not subshells
- Truncate logs before running: `: > logfile.log`
- No timeout commands on builds
- Quote variables: `"$var"`
- Use `set -e` for error handling

### `get_terminal_output`
**Purpose:** Get output from background terminal command  
**Usage:** Checking results of `isBackground=true` commands  
**Parameters:**
- `id` (required): Terminal ID from `run_in_terminal`

### `terminal_last_command`
**Purpose:** Get last command run in active terminal  
**Usage:** Checking what was executed

### `terminal_selection`
**Purpose:** Get current terminal selection  
**Usage:** Reading selected terminal text

### `create_and_run_task`
**Purpose:** Create/run tasks via tasks.json  
**Usage:** Creating build/run tasks for workspace  
**Parameters:**
- `task` (required): Task configuration (label, type, command, args, etc.)
- `workspaceFolder` (required): Workspace path

**When to use:** User asks to build/run/launch and no tasks.json exists

---

## ✅ TASK MANAGEMENT

### `manage_todo_list`
**Purpose:** Track multi-step work progress  
**Usage:** Planning and tracking complex tasks  
**Parameters:**
- `operation` (required): `write` or `read`
- `todoList` (optional): Array of todo items (for write)

**When to use:**
- Complex multi-step work
- Multiple user requests
- Tasks requiring careful sequencing
- Maintaining checkpoints

**When NOT to use:**
- Simple single-step operations

**Best practices:**
- Break work into logical, actionable steps
- Mark ONE task in-progress at a time
- Mark completed IMMEDIATELY after finishing
- Update frequently for visibility

**Todo item structure:**
- `id`: Sequential number
- `title`: 3-7 word action-oriented label
- `description`: Details, file paths, requirements
- `status`: `not-started`, `in-progress`, `completed`

---

## 🔌 VS CODE EXTENSIONS

### `install_extension`
**Purpose:** Install VS Code extensions  
**Usage:** Adding extensions during workspace setup  
**Parameters:**
- `id` (required): Extension ID (`publisher.extension`)
- `name` (required): Extension name

**When to use:** Only during new workspace creation process

### `run_vscode_command`
**Purpose:** Execute VS Code commands  
**Usage:** Running commands during workspace setup  
**Parameters:**
- `commandId` (required): Command ID
- `name` (required): Command description
- `args` (optional): Command arguments

**When to use:** Only during new workspace creation process

### `vscode_searchExtensions_internal`
**Purpose:** Search VS Code marketplace for extensions  
**Usage:** Finding extensions by category/keywords/ID  
**Parameters:**
- `category` (optional): Extension category (AI, Debuggers, Themes, etc.)
- `keywords` (optional): Array of search keywords
- `ids` (optional): Array of extension IDs

---

## 📊 SPECIALIZED TOOL CATEGORIES

The following tool groups require activation via `activate_*` tools:

### Migration & Assessment Tools
- `activate_migration_validation_and_management_tools` - Code migration validation
- `activate_knowledgebase_access_tools` - Migration knowledge base
- `activate_pre_migration_assessment_tools` - Java app assessment
- `activate_migration_assessment_tools` - Cloud migration readiness
- `activate_python_migration_management_tools` - Python migration workflow

### Java Development Tools
- `activate_java_project_build_and_testing_tools` - Maven/Gradle builds, test generation
- `activate_java_upgrade_tools` - Java version upgrade workflow
- `activate_jdk_and_maven_installation_tools` - JDK/Maven installation
- `activate_java_debugging_control_tools` - Breakpoints, stepping
- `activate_java_debug_session_management_tools` - Debug session control

Available after activation:
- `build_java_project` - Compile with Maven/Gradle
- `generate_tests_for_java` - Auto-generate test cases
- `run_tests_for_java` - Execute tests
- `generate_upgrade_plan` - Plan Java upgrades
- `confirm_upgrade_plan` - Review upgrade plan
- `setup_upgrade_environment` - Prepare build tools/JDKs
- `summarize_upgrade` - Document upgrade changes
- `install_jdk` - Download/install specific JDK
- `install_maven` - Install Maven
- `list_jdks` - List installed JDKs
- `list_mavens` - List installed Mavens
- `validate_behavior_changes` - Verify logic preservation
- `validate_cves_for_java` - Check dependency vulnerabilities
- `debug_java_application` - Launch debugger
- `debug_step_operation` - Control execution flow
- `set_java_breakpoint` - Set breakpoints
- `remove_java_breakpoints` - Remove breakpoints
- `get_debug_session_info` - Get session status
- `get_debug_threads` - List debug threads
- `stop_debug_session` - End debug session
- `get_debug_stack_trace` - Get call stack
- `get_debug_variables` - Inspect variables
- `evaluate_debug_expression` - Evaluate expressions

### Python Development Tools
- `activate_python_code_validation_and_execution` - Syntax check, code execution
- `activate_import_analysis_and_dependency_management` - Import analysis
- `activate_python_environment_management` - Environment control
- `activate_workspace_structure_and_file_management` - Workspace navigation

### Database Tools
- `activate_mssql_database_exploration_tools` - SQL Server metadata

Available after activation:
- `mssql_connect` - Connect to SQL Server
- `mssql_disconnect` - Disconnect
- `mssql_list_databases` - List databases
- `mssql_list_tables` - List tables
- `mssql_list_views` - List views
- `mssql_list_schemas` - List schemas
- `mssql_list_functions` - List functions
- `mssql_change_database` - Switch database
- `mssql_get_connection_details` - Get connection info
- `mssql_list_servers` - List available servers

### Container Tools
- `container-tools_get-config` - Get Docker/compose CLI config

**Important:** ALWAYS call before generating container/compose commands

### Python Language Server (Pylance)
- `mcp_pylance_mcp_s_pylanceDocuments` - Search Pylance documentation
- `mcp_pylance_mcp_s_pylanceInvokeRefactoring` - Apply Python refactorings

Available refactorings:
- `source.unusedImports` - Remove unused imports
- `source.convertImportFormat` - Convert absolute/relative imports
- `source.convertImportStar` - Convert wildcard imports to explicit
- `source.addTypeAnnotation` - Add type hints
- `source.fixAll.pylance` - Apply all auto-fixes

---

## 🚀 APPLICATION MODERNIZATION TOOLS

### Core Migration Tools

#### `appmod-run-task`
**Purpose:** Execute complete migration workflow for technology changes  
**Usage:** Migrating between different technologies (AWS→Azure, Kafka→Event Hubs, etc.)  
**Parameters:**
- `workspacePath` (required): Source code path
- `language` (required): `java` or `python`
- `scenario` (optional): Migration scenario description
- `taskId` (optional): Specific task ID
- `kbId` (optional): Knowledge base ID

**When to use:** Technology migrations (NOT version upgrades like Java 8→21 or Spring Boot 2→3)

**Example scenarios:**
- AWS S3 → Azure Storage Blob
- Kafka → Event Hubs
- RabbitMQ → Azure Service Bus
- Log output → console
- Database migrations

#### `appmod-search-file`
**Purpose:** Search project files by pattern and query  
**Usage:** Finding specific code/resources during migration  
**Parameters:**
- `workspacePath` (required): Project path
- `query` (required): Search string/regex
- `includePattern` (required): Glob pattern (e.g., `**/*.java`)
- `sessionId` (required): Migration session ID

#### `appmod-completeness-validation`
**Purpose:** Validate migration completeness  
**Usage:** Verify all old technology references removed  
**Parameters:**
- `workspacePath` (required): Project path
- `migrationScenario` (required): Migration description
- `kbIds` (required): Knowledge base IDs used
- `sessionId` (required): Migration session ID
- `language` (required): `java` or `python`

**Returns:** Validation guidelines, missing removals, incomplete transformations

#### `appmod-create-migration-summary`
**Purpose:** Generate migration session summary  
**Usage:** Document migration results  
**Parameters:**
- `workspacePath` (required): Project path
- `kbIds` (required): Knowledge base IDs
- `sessionId` (required): Migration session ID
- `language` (required): `java` or `python`
- `status` (required): Build/test/validation status
- `versionControlSummary` (required): Git activity summary

### Utility Tools

#### `appmod-get-vscode-config`
**Purpose:** Retrieve extension configuration  
**Usage:** Checking GitHub Copilot app modernization settings  
**Parameters:**
- `configName` (required): Configuration name

#### `appmod-preview-markdown`
**Purpose:** Preview Markdown files  
**Usage:** View migration documentation  
**Parameters:**
- `markdownFile` (optional): Absolute path to .md file

### Migration Assessment

#### `migration_assessmentReport`
**Purpose:** Display migration assessment webview  
**Usage:** Show assessment results after running assessment  

**When to use:** Immediately after successful migration assessment

#### `migration_assessmentReportsList`
**Purpose:** List all assessment reports  
**Usage:** View historical assessments

#### `uploadAssessSummaryReport`
**Purpose:** Upload assessment summary to GitHub issue  
**Usage:** Sharing assessment results  
**Parameters:**
- `owner` (required): Repository owner
- `repo` (required): Repository name
- `issueNumber` (required): Issue number

---

## 📋 TOOL USAGE PRINCIPLES

### Priority Rules (from AGENTS.md)

1. **Minimize Terminal Usage**
   - ✅ Prefer internal tools over shell commands
   - ✅ Use `create_file` not `cat > file`
   - ✅ Use `read_file` not `cat`/`head`/`tail`
   - ✅ Use `replace_string_in_file` not `sed -i`
   - ✅ Use `grep_search` not `grep`/`find`/`ag`

2. **Exceptions Requiring Terminal**
   - Build commands (Gradle, Maven, make)
   - Deployment (ADB, device commands)
   - Git operations
   - System commands
   - Complex pipelines

3. **File Path Requirements**
   - ALWAYS use absolute paths
   - Handle URIs with schemes (untitled:, vscode-userdata:)

4. **Parallel Execution**
   - Call independent tools in parallel
   - Don't parallelize semantic_search
   - Wait for dependencies before dependent calls

5. **Context Gathering**
   - Read large ranges over many small reads
   - Batch discovery operations in parallel
   - Get enough context to act, then proceed

---

## 🔍 TOOL SELECTION FLOWCHART

**Creating Files:**
1. Need to create file? → `create_file` ✅
2. Need directory first? → `create_directory` or just use `create_file` (auto-creates)

**Reading Files:**
1. Know exact file? → `read_file` ✅
2. Need to find files? → `file_search` (by name) or `grep_search` (by content)
3. Exploring codebase? → `semantic_search`

**Editing Files:**
1. One edit? → `replace_string_in_file`
2. Multiple edits? → `multi_replace_string_in_file` ✅

**Searching Code:**
1. Know exact text? → `grep_search` with `isRegexp=false`
2. Know pattern? → `grep_search` with `isRegexp=true`
3. Natural language? → `semantic_search`
4. Find usages? → `list_code_usages`

**Running Commands:**
1. Can use internal tool? → Use it ✅
2. Build/test/deploy? → `run_in_terminal`
3. Python command? → `get_python_executable_details` first, then `run_in_terminal`

**Project Setup:**
1. Full project scaffold? → `create_new_workspace` then `get_project_setup_info`
2. Just files? → `create_file` ✅

---

## 📚 RELATED DOCUMENTATION

- **AGENTS.md** - Complete agent operational protocols
- **AI_RULES.md** - Comprehensive agent rules and standards
- **INITIAL_PROMPT.md** - Project context and guidelines

---

**END OF TOOL REFERENCE**
