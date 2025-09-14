#!/bin/bash

# post_build_bak_manager.sh
# Script to restore .bak files after Android builds
# This restores all .bak files that were moved by pre_build_bak_manager.sh

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
    echo -e "${BLUE}[$(date '+%H:%M:%S')] POST-BUILD${NC} $1"
}

# Function to log errors
error() {
    echo -e "${RED}[ERROR] POST-BUILD${NC} $1" >&2
}

# Function to log warnings
warn() {
    echo -e "${YELLOW}[WARN] POST-BUILD${NC} $1"
}

# Function to log success
success() {
    echo -e "${GREEN}[SUCCESS] POST-BUILD${NC} $1"
}

echo -e "${BLUE}🔧 Post-Build: Restoring .bak Files${NC}"
echo "========================================="

# Check if we're in the right directory
if [ ! -f "build.gradle.kts" ]; then
    error "Not in project root directory (build.gradle.kts not found)"
    exit 1
fi

# Check if registry file exists
if [ ! -f "$BAK_REGISTRY_FILE" ]; then
    warn "No .bak file registry found ($BAK_REGISTRY_FILE)"
    warn "Either no .bak files were moved, or pre_build_bak_manager.sh was not run"
    exit 0
fi

# Check if temporary directory exists
if [ ! -d "$BAK_TEMP_DIR" ]; then
    error "Temporary storage directory not found ($BAK_TEMP_DIR)"
    error "Cannot restore .bak files"
    exit 1
fi

log "Restoring .bak files from temporary location..."

restore_count=0
failed_count=0

while IFS= read -r rel_path; do
    if [ -n "$rel_path" ]; then
        temp_file_path="$BAK_TEMP_DIR/$rel_path"
        original_path="$rel_path"
        
        if [ -f "$temp_file_path" ]; then
            # Ensure the original directory exists
            mkdir -p "$(dirname "$original_path")"
            
            # Move the file back
            mv "$temp_file_path" "$original_path"
            
            log "  Restored: $rel_path"
            ((restore_count++))
        else
            warn "Temporary file not found: $temp_file_path"
            ((failed_count++))
        fi
    fi
done < "$BAK_REGISTRY_FILE"

# Clean up temporary files
log "Cleaning up temporary storage..."

if [ -d "$BAK_TEMP_DIR" ]; then
    rm -rf "$BAK_TEMP_DIR"
    log "Removed temporary directory: $BAK_TEMP_DIR"
fi

if [ -f "$BAK_REGISTRY_FILE" ]; then
    rm -f "$BAK_REGISTRY_FILE"
    log "Removed registry file: $BAK_REGISTRY_FILE"
fi

# Report results
if [ $failed_count -eq 0 ]; then
    success "Successfully restored $restore_count .bak files to original locations"
else
    warn "Restored $restore_count .bak files, but $failed_count files could not be restored"
fi

success "Post-build .bak file management completed"
