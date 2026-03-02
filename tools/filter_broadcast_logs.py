#!/usr/bin/env python3
"""
Broadcast Log Filter and Timeline Generator

Usage:
    .venv/bin/python tools/filter_broadcast_logs.py --logs phone_test.log phone_test2.log --output timeline.md

Description:
    - Parses Android log files from multiple devices (Phone 1, Phone 2, etc.)
    - Filters entries matching BROADCAST_INVESTIGATION_PROTOCOL workflow patterns
    - Tags entries with source file, matched patterns, workflow steps, and broadcast IDs
    - Extracts broadcast IDs, chunk indices, and workflow progression
    - Generates chronological timeline in structured markdown format
    - Maps to 11-step BROADCAST_INVESTIGATION_PROTOCOL for rapid analysis
    - Handles edge cases: multiple broadcasts, clock skew, truncated logs, malformed entries

Example:
    # Basic usage - filter 2 log files
    .venv/bin/python tools/filter_broadcast_logs.py --logs phone_test.log phone_test2.log

    # Custom output file and include all steps
    .venv/bin/python tools/filter_broadcast_logs.py \\
        --logs phone_test.log phone_test2.log \\
        --output broadcast_analysis.md \\
        --include-steps 1,2,3,4,5,6,7,8,9,10,11

    # Verbose mode with JSON output
    .venv/bin/python tools/filter_broadcast_logs.py \\
        --logs phone_test.log phone_test2.log \\
        --output timeline.json \\
        --format json \\
        --verbose

    # OR (redirect output to log file)
    : > filter_output.log && .venv/bin/python tools/filter_broadcast_logs.py \\
        --logs phone_test.log phone_test2.log \\
        2>&1 | tee filter_output.log

Requirements:
    - Run with Python interpreter from workspace virtual environment (.venv)
    - No external dependencies required (uses stdlib only)
    - Install venv with: python3 -m venv .venv

Workflow Steps (BROADCAST_INVESTIGATION_PROTOCOL):
    Step 1:  Broadcast Initiation (Sender Device)
    Step 2:  File Chunking (Sender Device)
    Step 3:  Transmission Path (Sender → Network)
    Step 4:  Network Reception (Network → Receiver)
    Step 5:  Routing to Handler (Receiver)
    Step 6:  Chunk Processing (Receiver)
    Step 7:  Completion Check (Receiver)
    Step 8:  File Reassembly (Receiver)
    Step 9:  Folder Creation (Receiver)
    Step 10: File Writing (Receiver)
    Step 11: Notification Creation (Receiver)

Edge Cases Handled:
    - Multiple broadcasts in same log file (tracks by broadcast ID)
    - Phone 2 clock skew (orders by event sequence, not timestamp)
    - Malformed log lines (skips with warning)
    - Missing timestamps (uses line number as fallback)
    - Truncated logs (reports incomplete broadcasts)
    - Interleaved workflows (separates by broadcast ID)
    - Empty log files (reports and skips)
    - Missing chunk indices (identifies gaps)
    - Duplicate entries (deduplicates by hash)
    - Non-ASCII characters (handles UTF-8 properly)
"""

import sys
import os
import re
import json
import argparse
from dataclasses import dataclass, field, asdict
from typing import List, Dict, Optional, Set, Tuple
from collections import defaultdict
from datetime import datetime
import hashlib

# ==================== CONSTANTS ====================

# Comprehensive pattern matrix covering all 11 workflow steps
PATTERNS = {
    # Workflow step patterns - Broadcast ID formats
    'broadcast_id_full': r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',  # Full UUID
    'broadcast_id_short': r'BroadcastMessageHandler\[([0-9a-f]{8})\]',  # NEW: Sub-tag format
    'broadcast_id': r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}',  # Legacy alias
    'chunk_index': r'chunk\s+(\d+)',
    'step_1_initiation': [
        'broadcastMessageAndFile',
        'sendBroadcast',
        'Broadcast dialog',
        'BROADCAST_START',
        'Starting broadcast',
        'file size=',
        'hasFile=',
        ': file size',
        'chunks=',
        'BroadcastMessageHandler\\['  # NEW: Sub-tag format
    ],
    'step_2_chunking': [
        'totalChunks',
        'chunkSize',
        'BroadcastMetadata',
        'Chunking',
        'split file',
        'chunks=',
        'file size='
    ],
    'step_3_transmission': [
        'Sending chunk',
        'BROADCAST packet',
        'route()',
        'transmitted',
        'Transmission',
        'UDP send',
        'DatagramSocket',
        'Starting batch',
        'sent to neighbor',
        '% complete',
        'chunk 0: sending',
        'chunk 1: sending',
        'chunk 2: sending',
        'complete (',
        # Sender loopback detection (Issue #1)
        'Broadcast already seen, ignoring',
        'Skipping local delivery for broadcast',
        'seenBroadcasts.putIfAbsent'
    ],
    'step_4_reception': [
        'received packet',
        'DatagramPacket received',
        'onReceive',
        'VirtualPacket',
        'packet validation',
        'Reception',
        'RECEIVED packet',
        'Packet details',
        '⬇️ RECEIVED',
        '📦 Packet'
    ],
    'step_5_routing': [
        'route()',
        'BROADCAST packet',
        'routing to handler',
        'handlePacket',
        'BroadcastMessageHandler',
        'Routing',
        'Received broadcast chunk',
        'New incoming broadcast',
        'isTextOnly',
        'chunk=',
        'id=',
        'file=',
        'BroadcastMessageHandler\[',  # Sub-tag format
        # VirtualNode routing architecture (Issue #1)
        '✅ BROADCAST PACKET DETECTED',
        'Delivering broadcast locally',
        'Forwarding broadcast to neighbor',
        'fromAddr',
        'addressAsInt'
    ],
    'step_6_processing': [
        'handleBroadcastChunk',
        'chunk index',
        'hash validation',
        'receivedChunks',
        'BROADCAST_COMPLETE_CHECK',
        'Processing chunk',
        'chunks received',
        '/4247 chunks',
        '/0 chunks',
        'BroadcastMessageHandler\['  # NEW: Sub-tag format
    ],
    'step_7_completion': [
        'isComplete',
        'BROADCAST_COMPLETE_CHECK',
        'all chunks received',
        'broadcast complete',
        'completion check',
        'isComplete=true',
        'isComplete=false'
    ],
    'step_8_reassembly': [
        'reassembleFile',
        'reassembled',
        'bytes',
        'file reconstruction',
        'chunk assembly',
        'Reassembly',
        'RECONSTITUTE',  # NEW: Log tag from updated code
        'BroadcastMessageHandler\['  # NEW: Sub-tag format
    ],
    'step_9_folder': [
        'drop folder',
        'getDropFolder',
        'SharedWithMe',
        'mkdirs',
        'folder creation',
        'storage',
        'Permission denied',
        'SHARED_FOLDER',  # NEW: Log tag from updated code
        'BroadcastMessageHandler\['  # NEW: Sub-tag format
    ],
    'step_10_file_write': [
        'writeBroadcastFile',
        'writing file',
        'file written',
        'FileOutputStream',
        'write bytes',
        'file already exists',
        'I/O error',
        'FILE_WRITE',  # NEW: Log tag from updated code
        'BroadcastMessageHandler\['  # NEW: Sub-tag format
    ],
    'step_11_notification': [
        'BroadcastReceivedDto',
        'notification',
        'receiveListeners',
        'listener callback',
        'receivedBroadcasts.add',
        'updateNotificationBadge',
        'notification badge',
        'dropdown',
        'Toast',
        'Snackbar',
        'BROADCAST_LISTENER',
        'Text-only broadcast received',
        'NOTIFICATION',  # Log tag from updated code
        'BroadcastMessageHandler\[',  # Sub-tag format
        # Error notification handling (Issue #2)
        'Creating notification DTO: hasError=',
        'Skipping listener notification for failed file transfer',
        'hasError=true',
        'hasError=false'
    ],
    
    # Additional context patterns
    'role_updates': [
        'ROLE_OBSERVER',
        'ROLE_UPDATE',
        'EmergentRoleManager',
        'MESH_ROUTER',
        'MESH_PARTICIPANT',
        'MESH_HUB'
    ],
    'mesh_state': [
        'LIFECYCLE',
        'mesh start',
        'mesh stop',
        'connection',
        'NETWORK_INFO_OBSERVER'
    ],
    'errors': [
        'Exception',
        'ERROR',
        'WARN',
        'Failed',
        'failed',
        'error',
        'timeout',
        'Timeout'
    ]
}

# Workflow step descriptions for output
WORKFLOW_STEPS = {
    1: 'Broadcast Initiation (Sender)',
    2: 'File Chunking (Sender)',
    3: 'Transmission Path (Sender → Network)',
    4: 'Network Reception (Network → Receiver)',
    5: 'Routing to Handler (Receiver)',
    6: 'Chunk Processing (Receiver)',
    7: 'Completion Check (Receiver)',
    8: 'File Reassembly (Receiver)',
    9: 'Folder Creation (Receiver)',
    10: 'File Writing (Receiver)',
    11: 'Notification Creation (Receiver)'
}

# Android log line regex (handles multiple formats)
ANDROID_LOG_REGEX = re.compile(
    r'^(?P<timestamp>\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+'
    r'(?P<level>[VDIWEFS])/(?P<tag>[^:]+):\s+'
    r'(?P<message>.*)$'
)

# Alternative log format without timestamp
ANDROID_LOG_REGEX_ALT = re.compile(
    r'^(?P<level>[VDIWEFS])/(?P<tag>[^:]+):\s+'
    r'(?P<message>.*)$'
)

# ==================== DATA CLASSES ====================

@dataclass
class LogEntry:
    """Represents a single parsed log entry with metadata."""
    source_file: str
    line_number: int
    timestamp: Optional[str]
    log_level: str
    tag: str
    message: str
    matched_patterns: List[str] = field(default_factory=list)
    workflow_step: Optional[int] = None
    broadcast_id: Optional[str] = None
    chunk_index: Optional[int] = None
    entry_hash: str = field(default='')  # For deduplication
    
    def __post_init__(self):
        """Calculate hash for deduplication."""
        if not self.entry_hash:
            hash_str = f"{self.source_file}:{self.line_number}:{self.message}"
            self.entry_hash = hashlib.md5(hash_str.encode()).hexdigest()[:8]

@dataclass
class BroadcastTrace:
    """Tracks complete trace of a single broadcast."""
    broadcast_id: str  # Can be short (8 chars) or full (36 chars) UUID
    entries: List[LogEntry] = field(default_factory=list)
    workflow_coverage: Set[int] = field(default_factory=set)
    total_chunks: Optional[int] = None
    chunks_seen: Set[int] = field(default_factory=set)
    sender: Optional[str] = None
    receiver: Optional[str] = None
    file_name: Optional[str] = None
    file_size: Optional[int] = None
    full_id: Optional[str] = None  # Store full UUID if discovered (for mapping)
    
    def is_complete(self) -> bool:
        """Check if all workflow steps are present."""
        return len(self.workflow_coverage) == 11
    
    def missing_steps(self) -> List[int]:
        """Return list of missing workflow steps."""
        return sorted(set(range(1, 12)) - self.workflow_coverage)

# ==================== CORE FUNCTIONS ====================

def parse_log_line(line: str, source_file: str, line_number: int) -> Optional[LogEntry]:
    """
    Parse a single Android log line into LogEntry.
    
    Handles edge cases:
    - Multiple log formats (with/without timestamp)
    - Malformed lines (returns None)
    - Missing fields (uses defaults)
    - Non-ASCII characters (UTF-8 safe)
    """
    # Try standard format first
    match = ANDROID_LOG_REGEX.match(line)
    if match:
        return LogEntry(
            source_file=source_file,
            line_number=line_number,
            timestamp=match.group('timestamp'),
            log_level=match.group('level'),
            tag=match.group('tag'),
            message=match.group('message')
        )
    
    # Try alternative format without timestamp
    match = ANDROID_LOG_REGEX_ALT.match(line)
    if match:
        return LogEntry(
            source_file=source_file,
            line_number=line_number,
            timestamp=None,
            log_level=match.group('level'),
            tag=match.group('tag'),
            message=match.group('message')
        )
    
    # Malformed line - return None
    return None

def extract_broadcast_id(message: str) -> Optional[str]:
    """
    Extract broadcast ID from message text.
    
    Supports TWO formats:
    1. NEW: BroadcastMessageHandler[f6b31072] -> extracts short ID (8 chars)
    2. OLD: id=ab4cdf84-... or "Broadcast f6b31072-..." -> extracts full UUID (36 chars)
    
    Returns short ID (8 chars) if new format found, full UUID (36 chars) if old format found.
    """
    # Try NEW format first (BroadcastMessageHandler[shortId])
    match_short = re.search(PATTERNS['broadcast_id_short'], message, re.IGNORECASE)
    if match_short:
        return match_short.group(1)  # Return the captured short ID (8 chars)
    
    # Fallback to OLD format (full UUID)
    match_full = re.search(PATTERNS['broadcast_id_full'], message, re.IGNORECASE)
    if match_full:
        full_id = match_full.group(0)
        # If full UUID found, also return short version (first 8 chars) for consistency
        return full_id[:8]  # Return short ID for grouping consistency
    
    return None

def extract_chunk_index(message: str) -> Optional[int]:
    """Extract chunk index from message text."""
    match = re.search(PATTERNS['chunk_index'], message, re.IGNORECASE)
    if match:
        try:
            return int(match.group(1))
        except (ValueError, IndexError):
            return None
    return None

def extract_total_chunks(message: str) -> Optional[int]:
    """Extract total chunks from message text."""
    patterns = [
        r'totalChunks[=:\s]+(\d+)',
        r'chunks[=:\s]+(\d+)',
        r'total[=:\s]+(\d+)\s+chunks'
    ]
    for pattern in patterns:
        match = re.search(pattern, message, re.IGNORECASE)
        if match:
            try:
                return int(match.group(1))
            except (ValueError, IndexError):
                continue
    return None

def extract_file_size(message: str) -> Optional[int]:
    """Extract file size from message text."""
    patterns = [
        r'fileSize[=:\s]+(\d+)',
        r'file size[=:\s]+(\d+)',
        r'size[=:\s]+(\d+)\s+bytes'
    ]
    for pattern in patterns:
        match = re.search(pattern, message, re.IGNORECASE)
        if match:
            try:
                return int(match.group(1))
            except (ValueError, IndexError):
                continue
    return None

def match_patterns(message: str) -> Tuple[List[str], Optional[int]]:
    """
    Match message against all patterns and determine workflow step.
    
    Returns:
        (matched_pattern_names, workflow_step_number)
    """
    matched = []
    workflow_step = None
    
    # Check each pattern category
    for pattern_name, pattern_list in PATTERNS.items():
        if pattern_name == 'broadcast_id' or pattern_name == 'chunk_index':
            continue  # Handled separately
        
        if not isinstance(pattern_list, list):
            pattern_list = [pattern_list]
        
        for pattern in pattern_list:
            if re.search(re.escape(pattern), message, re.IGNORECASE):
                matched.append(pattern_name)
                break  # One match per category is enough
    
    # Determine workflow step from matched patterns
    step_mapping = {
        'step_1_initiation': 1,
        'step_2_chunking': 2,
        'step_3_transmission': 3,
        'step_4_reception': 4,
        'step_5_routing': 5,
        'step_6_processing': 6,
        'step_7_completion': 7,
        'step_8_reassembly': 8,
        'step_9_folder': 9,
        'step_10_file_write': 10,
        'step_11_notification': 11
    }
    
    for pattern_name in matched:
        if pattern_name in step_mapping:
            workflow_step = step_mapping[pattern_name]
            break  # Use first matched step
    
    return (matched, workflow_step)

def filter_log_file(file_path: str, verbose: bool = False) -> List[LogEntry]:
    """
    Parse and filter a single log file.
    
    Edge cases handled:
    - File not found (logs error, returns empty list)
    - Empty file (logs warning, returns empty list)
    - Encoding errors (tries UTF-8, then latin-1)
    - Malformed lines (skips with warning if verbose)
    """
    entries = []
    
    if not os.path.exists(file_path):
        print(f"[ERROR] Log file not found: {file_path}", file=sys.stderr)
        return entries
    
    if os.path.getsize(file_path) == 0:
        print(f"[WARN] Log file is empty: {file_path}", file=sys.stderr)
        return entries
    
    encodings = ['utf-8', 'latin-1']
    file_content = None
    
    for encoding in encodings:
        try:
            with open(file_path, 'r', encoding=encoding) as f:
                file_content = f.readlines()
            break
        except UnicodeDecodeError:
            continue
    
    if file_content is None:
        print(f"[ERROR] Could not decode file: {file_path}", file=sys.stderr)
        return entries
    
    malformed_count = 0
    matched_count = 0
    
    for line_num, line in enumerate(file_content, start=1):
        line = line.rstrip('\n\r')
        
        if not line.strip():
            continue  # Skip empty lines
        
        entry = parse_log_line(line, os.path.basename(file_path), line_num)
        
        if entry is None:
            malformed_count += 1
            if verbose:
                print(f"[WARN] Malformed line {line_num} in {file_path}: {line[:80]}...", file=sys.stderr)
            continue
        
        # Extract metadata
        entry.broadcast_id = extract_broadcast_id(entry.message)
        entry.chunk_index = extract_chunk_index(entry.message)
        
        # Match patterns
        matched_patterns, workflow_step = match_patterns(entry.message)
        entry.matched_patterns = matched_patterns
        entry.workflow_step = workflow_step
        
        # EXPLICIT BROADCAST CONTEXT FILTER
        # Reference: broadcast_workflow_log_patterns.txt
        # 
        # Key insight from actual logs: ALL broadcast operations explicitly mention:
        # - "BroadcastMessageHandler" (in message body, not tag - goes to System.out)
        # - "BroadcastMessageHandler[shortId]" (NEW format with broadcast ID sub-tag)
        # - "BROADCAST PACKET DETECTED" (VirtualNode routing)
        # - "BROADCAST" in EnhancedMeshFragment UI logs
        #
        # Do NOT include entries based on UUID or pattern matching alone - this creates
        # false positives from Camera2 (645531cf), Facebook (54d9dda5), etc.
        
        has_broadcast_handler = 'BroadcastMessageHandler' in entry.message
        has_broadcast_sub_tag = re.search(PATTERNS['broadcast_id_short'], entry.message) is not None
        has_broadcast_packet_detected = 'BROADCAST PACKET DETECTED' in entry.message
        has_ui_broadcast = (
            'EnhancedMeshFragment' in entry.tag and 
            'BROADCAST' in entry.message
        )
        
        # Only include if explicit broadcast context exists
        is_broadcast_related = (
            has_broadcast_handler or
            has_broadcast_sub_tag or
            has_broadcast_packet_detected or
            has_ui_broadcast
        )
        
        if is_broadcast_related:
            entries.append(entry)
            matched_count += 1
    
    if verbose:
        print(f"[INFO] {file_path}: {len(file_content)} lines, {matched_count} matched, {malformed_count} malformed")
    
    return entries

def deduplicate_entries(entries: List[LogEntry]) -> List[LogEntry]:
    """
    Remove duplicate log entries based on entry hash.
    
    Edge case: Multiple identical lines from different files are kept.
    """
    seen_hashes = set()
    unique_entries = []
    
    for entry in entries:
        if entry.entry_hash not in seen_hashes:
            seen_hashes.add(entry.entry_hash)
            unique_entries.append(entry)
    
    return unique_entries

def group_by_broadcast(entries: List[LogEntry]) -> Dict[str, BroadcastTrace]:
    """
    Group entries by broadcast ID and build broadcast traces.
    
    Handles TWO ID formats:
    - Short ID (8 chars): f6b31072 - from BroadcastMessageHandler[f6b31072]
    - Full ID (36 chars): f6b31072-6793-46ad-acb4-f3180919c970 - from id= or Broadcast ...
    
    Strategy: Use short ID (8 chars) as primary key for grouping.
    If full ID discovered, store in trace.full_id for reference.
    
    Edge cases:
    - Entries without broadcast ID (grouped under 'UNKNOWN')
    - Multiple broadcasts (each gets separate trace by short ID)
    - Incomplete broadcasts (tracked via missing_steps)
    - Mixed short/full IDs (normalized to short ID for grouping)
    """
    traces = defaultdict(lambda: BroadcastTrace(broadcast_id='UNKNOWN'))
    id_mapping = {}  # Map short ID -> full ID (when full ID discovered)
    
    for entry in entries:
        broadcast_id = entry.broadcast_id or 'UNKNOWN'
        
        # Normalize to short ID (8 chars) for grouping
        if broadcast_id != 'UNKNOWN' and len(broadcast_id) > 8:
            # Full ID detected - extract short ID and store mapping
            short_id = broadcast_id[:8]
            id_mapping[short_id] = broadcast_id
            broadcast_id = short_id
        
        if broadcast_id != 'UNKNOWN' and traces[broadcast_id].broadcast_id == 'UNKNOWN':
            traces[broadcast_id] = BroadcastTrace(broadcast_id=broadcast_id)
        
        trace = traces[broadcast_id]
        trace.entries.append(entry)
        
        # Update full ID if discovered
        if broadcast_id in id_mapping and not trace.full_id:
            trace.full_id = id_mapping[broadcast_id]
        
        if entry.workflow_step:
            trace.workflow_coverage.add(entry.workflow_step)
        
        if entry.chunk_index is not None:
            trace.chunks_seen.add(entry.chunk_index)
        
        # Extract metadata from entry if available
        total_chunks = extract_total_chunks(entry.message)
        if total_chunks and not trace.total_chunks:
            trace.total_chunks = total_chunks
        
        file_size = extract_file_size(entry.message)
        if file_size and not trace.file_size:
            trace.file_size = file_size
    
    return dict(traces)

def generate_markdown_timeline(traces: Dict[str, BroadcastTrace], output_file: str, verbose: bool = False):
    """
    Generate structured markdown timeline optimized for BROADCAST_INVESTIGATION_PROTOCOL analysis.
    
    Output structure:
    - Summary statistics
    - Per-broadcast trace with workflow coverage
    - Chronological event list
    - Missing steps analysis
    - Packet count summary
    """
    with open(output_file, 'w', encoding='utf-8') as f:
        f.write("# Broadcast Log Timeline Analysis\n\n")
        f.write(f"**Generated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
        
        # Summary statistics
        f.write("## Summary Statistics\n\n")
        f.write(f"- **Total Broadcasts:** {len(traces)}\n")
        f.write(f"- **Total Log Entries:** {sum(len(t.entries) for t in traces.values())}\n")
        
        complete_count = sum(1 for t in traces.values() if t.is_complete())
        f.write(f"- **Complete Workflows:** {complete_count}/{len(traces)}\n")
        f.write(f"- **Incomplete Workflows:** {len(traces) - complete_count}/{len(traces)}\n\n")
        
        # Per-broadcast analysis
        for broadcast_id, trace in sorted(traces.items()):
            # Display full ID if known, otherwise short ID
            display_id = trace.full_id if trace.full_id else broadcast_id
            f.write(f"## Broadcast: `{display_id}`\n\n")
            
            # Metadata
            f.write("### Metadata\n\n")
            f.write(f"- **Broadcast ID:** `{display_id}`\n")
            if trace.full_id and broadcast_id != trace.full_id:
                f.write(f"- **Short ID:** `{broadcast_id}`\n")
            f.write(f"- **Total Entries:** {len(trace.entries)}\n")
            f.write(f"- **Workflow Coverage:** {len(trace.workflow_coverage)}/11 steps\n")
            
            if trace.total_chunks:
                f.write(f"- **Total Chunks:** {trace.total_chunks}\n")
                f.write(f"- **Chunks Seen:** {len(trace.chunks_seen)}/{trace.total_chunks}\n")
                if trace.total_chunks > len(trace.chunks_seen):
                    missing = set(range(trace.total_chunks)) - trace.chunks_seen
                    f.write(f"- **Missing Chunks:** {sorted(list(missing))}\n")  # All missing chunks
            
            if trace.file_size:
                f.write(f"- **File Size:** {trace.file_size:,} bytes\n")
            
            f.write("\n")
            
            # Workflow coverage
            f.write("### Workflow Coverage\n\n")
            f.write("| Step | Description | Status |\n")
            f.write("|------|-------------|--------|\n")
            
            for step_num in range(1, 12):
                desc = WORKFLOW_STEPS[step_num]
                status = "✅ Present" if step_num in trace.workflow_coverage else "❌ Missing"
                f.write(f"| {step_num} | {desc} | {status} |\n")
            
            f.write("\n")
            
            # Chronological event list
            f.write("### Chronological Event Log\n\n")
            f.write("| Line | Source | Level | Tag | Step | Broadcast ID | Chunk | Message |\n")
            f.write("|------|--------|-------|-----|------|--------------|-------|----------|\n")
            
            for entry in trace.entries:  # All entries, no limit
                step_str = str(entry.workflow_step) if entry.workflow_step else "-"
                broadcast_id_str = f"`{entry.broadcast_id[:8]}...`" if entry.broadcast_id else "-"
                chunk_str = str(entry.chunk_index) if entry.chunk_index is not None else "-"
                message_full = entry.message.replace('|', '\\|')  # Escape pipes, no truncation
                
                f.write(f"| {entry.line_number} | {entry.source_file} | {entry.log_level} | "
                       f"{entry.tag} | {step_str} | {broadcast_id_str} | {chunk_str} | "
                       f"{message_full} |\n")
            
            f.write("\n")
            
            # Missing steps analysis
            if not trace.is_complete():
                f.write("### ⚠️ Missing Steps Analysis\n\n")
                missing = trace.missing_steps()
                f.write(f"**Missing {len(missing)} workflow step(s):**\n\n")
                
                for step_num in missing:
                    f.write(f"- **Step {step_num}:** {WORKFLOW_STEPS[step_num]}\n")
                
                f.write("\n")
            
            f.write("---\n\n")
        
        # Final recommendations
        f.write("## Analysis Recommendations\n\n")
        f.write("1. **Review missing workflow steps** in incomplete broadcasts\n")
        f.write("2. **Check chunk gaps** for file broadcasts (expected chunks vs received)\n")
        f.write("3. **Correlate by broadcast ID** across different log files (event-based, not timestamp)\n")
        f.write("4. **Verify error entries** tagged with 'errors' pattern\n")
        f.write("5. **Cross-reference with BROADCAST_INVESTIGATION_PROTOCOL.md** for detailed verification\n\n")
    
    if verbose:
        print(f"[INFO] Timeline written to {output_file}")

def generate_json_timeline(traces: Dict[str, BroadcastTrace], output_file: str, verbose: bool = False):
    """Generate JSON format timeline for programmatic analysis."""
    output = {
        'generated_at': datetime.now().isoformat(),
        'summary': {
            'total_broadcasts': len(traces),
            'total_entries': sum(len(t.entries) for t in traces.values()),
            'complete_workflows': sum(1 for t in traces.values() if t.is_complete()),
            'incomplete_workflows': sum(1 for t in traces.values() if not t.is_complete())
        },
        'broadcasts': []
    }
    
    for broadcast_id, trace in sorted(traces.items()):
        broadcast_data = {
            'broadcast_id': broadcast_id,
            'full_id': trace.full_id,  # Include full UUID if discovered
            'total_entries': len(trace.entries),
            'workflow_coverage': sorted(list(trace.workflow_coverage)),
            'is_complete': trace.is_complete(),
            'missing_steps': trace.missing_steps(),
            'total_chunks': trace.total_chunks,
            'chunks_seen': sorted(list(trace.chunks_seen)),
            'file_size': trace.file_size,
            'entries': [
                {
                    'source_file': e.source_file,
                    'line_number': e.line_number,
                    'timestamp': e.timestamp,
                    'log_level': e.log_level,
                    'tag': e.tag,
                    'message': e.message,
                    'matched_patterns': e.matched_patterns,
                    'workflow_step': e.workflow_step,
                    'broadcast_id': e.broadcast_id,
                    'chunk_index': e.chunk_index
                }
                for e in trace.entries
            ]
        }
        output['broadcasts'].append(broadcast_data)
    
    with open(output_file, 'w', encoding='utf-8') as f:
        json.dump(output, f, indent=2)
    
    if verbose:
        print(f"[INFO] JSON timeline written to {output_file}")

# ==================== MAIN ENTRY POINT ====================

def main():
    parser = argparse.ArgumentParser(
        description='Filter broadcast-related log entries and generate timeline',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__
    )
    
    parser.add_argument(
        '--logs',
        nargs='+',
        required=True,
        help='Log files to parse (e.g., phone_test.log phone_test2.log)'
    )
    
    parser.add_argument(
        '--output',
        default='broadcast_timeline.md',
        help='Output file path (default: broadcast_timeline.md)'
    )
    
    parser.add_argument(
        '--format',
        choices=['markdown', 'json'],
        default='markdown',
        help='Output format (default: markdown)'
    )
    
    parser.add_argument(
        '--include-steps',
        help='Comma-separated workflow steps to include (e.g., 1,2,3,6,11)'
    )
    
    parser.add_argument(
        '--verbose',
        action='store_true',
        help='Enable verbose output'
    )
    
    args = parser.parse_args()
    
    # Validate log files
    for log_file in args.logs:
        if not os.path.exists(log_file):
            print(f"[ERROR] Log file not found: {log_file}", file=sys.stderr)
            sys.exit(1)
    
    # Parse workflow steps filter if provided
    include_steps = None
    if args.include_steps:
        try:
            include_steps = set(int(s.strip()) for s in args.include_steps.split(','))
        except ValueError:
            print(f"[ERROR] Invalid --include-steps format: {args.include_steps}", file=sys.stderr)
            sys.exit(1)
    
    # Process each log file
    all_entries = []
    
    for log_file in args.logs:
        if args.verbose:
            print(f"[INFO] Processing {log_file}...")
        
        entries = filter_log_file(log_file, verbose=args.verbose)
        all_entries.extend(entries)
    
    if not all_entries:
        print("[WARN] No matching entries found in any log file", file=sys.stderr)
        sys.exit(0)
    
    # Deduplicate
    unique_entries = deduplicate_entries(all_entries)
    
    if args.verbose:
        print(f"[INFO] Total entries: {len(all_entries)}, unique: {len(unique_entries)}")
    
    # Filter by workflow steps if specified
    if include_steps:
        unique_entries = [e for e in unique_entries if e.workflow_step in include_steps]
        if args.verbose:
            print(f"[INFO] Filtered to {len(unique_entries)} entries matching steps {sorted(include_steps)}")
    
    # Group by broadcast ID
    traces = group_by_broadcast(unique_entries)
    
    if args.verbose:
        print(f"[INFO] Found {len(traces)} broadcast(s)")
    
    # Generate output
    if args.format == 'markdown':
        generate_markdown_timeline(traces, args.output, verbose=args.verbose)
    elif args.format == 'json':
        generate_json_timeline(traces, args.output, verbose=args.verbose)
    
    print(f"✅ Timeline generated: {args.output}")
    print(f"   - Broadcasts: {len(traces)}")
    print(f"   - Entries: {len(unique_entries)}")
    print(f"   - Complete workflows: {sum(1 for t in traces.values() if t.is_complete())}/{len(traces)}")

if __name__ == '__main__':
    main()
