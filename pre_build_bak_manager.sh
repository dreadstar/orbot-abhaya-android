#!/bin/bash

# pre_build_bak_manager.sh
# Script to temporarily move .bak files before Android builds
# This prevents Android Resource Manager from processing .bak files during compilation

set -e  # Exit on any error

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BAK_TEMP_DIR=".bak_temp_storage"
BAK_REGISTRY_FILE=".bak_file_registry.txt"

# Function to log with timestamp
log() {
    echo -e "${BLUE}[$(date '+%H:%M:%S')] PRE-BUILD${NC} $1"
}

# Function to log errors
error() {
    echo -e "${RED}[ERROR] PRE-BUILD${NC} $1" >&2
}

# Function to log success
success() {
    echo -e "${GREEN}[SUCCESS] PRE-BUILD${NC} $1"
}

echo -e "${BLUE}🔧 Pre-Build: Moving .bak Files${NC}"
echo "======================================="

# Check if we're in the right directory
if [ ! -f "build.gradle.kts" ]; then
    error "Not in project root directory (build.gradle.kts not found)"
    exit 1
fi

# Clean up any previous temporary storage
if [ -d "$BAK_TEMP_DIR" ]; then
    log "Cleaning up previous temporary storage..."
    rm -rf "$BAK_TEMP_DIR"
fi

if [ -f "$BAK_REGISTRY_FILE" ]; then
    rm -f "$BAK_REGISTRY_FILE"
fi

log "Moving .bak files to temporary location..."

# Create temporary directory
mkdir -p "$BAK_TEMP_DIR"

# Clear the registry file
> "$BAK_REGISTRY_FILE"

# Find all .bak files under app/ directory
bak_count=0
while IFS= read -r -d '' bak_file; do
    if [ -f "$bak_file" ]; then
        # Get relative path from project root
        rel_path="${bak_file#./}"
        
        # Create the directory structure in temp location
        temp_file_path="$BAK_TEMP_DIR/$rel_path"
        temp_dir="$(dirname "$temp_file_path")"
        
        mkdir -p "$temp_dir"
        
        # Move the file
        mv "$bak_file" "$temp_file_path"
        
        # Record in registry for restoration
        echo "$rel_path" >> "$BAK_REGISTRY_FILE"
        
        log "  Moved: $rel_path"
        ((bak_count++))
    fi
done < <(find app/ -name "*.bak" -type f -print0 2>/dev/null)

if [ $bak_count -eq 0 ]; then
    log "No .bak files found to move"
else
    success "Moved $bak_count .bak files to temporary storage"
fi

success "Pre-build .bak file management completed"
echo "You can now run your Gradle build commands safely."
echo "Remember to run './post_build_bak_manager.sh' after the build!"
