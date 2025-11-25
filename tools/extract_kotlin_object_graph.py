"""
Kotlin Object/Class Dependency Graph Extractor

Usage:
    .venv/bin/python tools/extract_kotlin_object_graph.py <source_folder>

Description:
    - Scans the given <source_folder> for all .kt files.
    - Extracts all class and object definitions and their relationships (internal only).
    - Ignores external package dependencies.
    - Outputs results to diagrams/Meshrabiy-dependency-graph-YYYYMMDD+HHmm/dependency-map.json
    - Output folder is auto-generated using current EST date/time for repeatable runs.
    - All steps and errors are logged to extract_graph_output.log and stdout.

Example:
    .venv/bin/python tools/extract_kotlin_object_graph.py Meshrabiya/

    OR
    (from the root folder)
    : > extract_graph_output.log && .venv/bin/python tools/extract_kotlin_object_graph.py Meshrabiya/ 2>&1 | tee extract_graph_output.log

Requirements:
    - Run with the Python interpreter from the workspace virtual environment (.venv).
    - Required packages: networkx, matplotlib, pytz
    - Install dependencies with: .venv/bin/pip install networkx matplotlib pytz
"""
import os
import re
import sys
import json
from datetime import datetime
from collections import defaultdict

def log(msg, log_lines):
    print(msg)
    log_lines.append(msg)
    try:
        with open('extract_graph_output.log', 'a', encoding='utf-8') as logf:
            logf.write(msg + '\n')
    except Exception as e:
        print(f'[FATAL] Could not write to log file: {e}')

CLASS_REGEX = re.compile(r'\b(class|object)\s+(\w+)')
REF_REGEX = re.compile(r'\b([A-Z][A-Za-z0-9_]*)\b')
EXCLUDE_PACKAGES = [
    'android.', 'kotlin.', 'org.', 'javax.', 'java.', 'com.google.', 'com.squareup.'
]

def scan_kotlin_files(root, log_lines):
    kt_files = []
    for dirpath, _, filenames in os.walk(root):
        for fname in filenames:
            if fname.endswith('.kt'):
                kt_files.append(os.path.join(dirpath, fname))
    log(f'[DEBUG] scan_kotlin_files found {len(kt_files)} files', log_lines)
    return kt_files

def extract_classes_and_objects(file_path, log_lines):
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        classes = []
        for match in CLASS_REGEX.finditer(content):
            kind, name = match.groups()
            classes.append({'type': kind, 'name': name, 'file': os.path.relpath(file_path)})
        log(f'[DEBUG] extract_classes_and_objects found {len(classes)} in {file_path}', log_lines)
        return classes
    except Exception as e:
        log(f'[ERROR] Failed to extract classes/objects from {file_path}: {e}', log_lines)
        return []

def extract_relationships(file_path, class_names, log_lines):
    edges = []
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            lines = f.readlines()
        for i, line in enumerate(lines):
            for ref in REF_REGEX.findall(line):
                if ref in class_names:
                    edges.append({'source': None, 'target': ref, 'file': os.path.relpath(file_path), 'line': i+1})
        log(f'[DEBUG] extract_relationships found {len(edges)} in {file_path}', log_lines)
    except Exception as e:
        log(f'[ERROR] Failed to extract relationships from {file_path}: {e}', log_lines)
    return edges

def main():
    log_lines = []
    log('[DEBUG] Entered main()', log_lines)
    try:
        import pytz
        if len(sys.argv) < 2:
            log('[ERROR] Usage: python extract_kotlin_object_graph.py <source_path>', log_lines)
            sys.exit(1)
        source_path = sys.argv[1]
        est = pytz.timezone('US/Eastern')
        now_est = datetime.now(est)
        folder_name = f"Meshrabiy-dependency-graph-{now_est.strftime('%Y%m%d+%H%M')}"
        output_dir = os.path.join('diagrams', folder_name)
        log(f'[DEBUG] Output folder will be: {output_dir}', log_lines)
        # Always create a new folder, never reuse
        if os.path.exists(output_dir):
            log(f'[ERROR] Output directory already exists: {output_dir}', log_lines)
            suffix = 1
            while os.path.exists(f"{output_dir}_{suffix}"):
                suffix += 1
            output_dir = f"{output_dir}_{suffix}"
            log(f'[INFO] Using new output directory: {output_dir}', log_lines)
        try:
            os.makedirs(output_dir, exist_ok=False)
            log(f'[INFO] Output directory created: {output_dir}', log_lines)
        except Exception as e:
            log(f'[ERROR] Failed to create output directory: {output_dir}\nError: {e}', log_lines)
            sys.exit(2)
        # Step 1: Scan for .kt files
        kt_files = scan_kotlin_files(source_path, log_lines)
        # Step 2: Extract all class/object definitions
        nodes = []
        class_names = set()
        file_class_map = defaultdict(list)
        for fpath in kt_files:
            classes = extract_classes_and_objects(fpath, log_lines)
            for c in classes:
                nodes.append({'id': c['name'], 'type': c['type'], 'file': c['file']})
                class_names.add(c['name'])
                file_class_map[fpath].append(c['name'])
        log(f'[DEBUG] Extracted {len(nodes)} classes/objects', log_lines)
        # Step 3: Extract relationships
        edges = []
        for fpath in kt_files:
            rels = extract_relationships(fpath, class_names, log_lines)
            srcs = file_class_map.get(fpath, [])
            for rel in rels:
                for src in srcs:
                    edges.append({'source': src, 'target': rel['target'], 'file': rel['file'], 'line': rel['line']})
        log(f'[DEBUG] Extracted {len(edges)} relationships', log_lines)
        # Fail-fast if no nodes or edges extracted
        if not nodes:
            log('[FATAL] No nodes extracted. Check source path and class/object definitions.', log_lines)
            sys.exit(10)
        if not edges:
            log('[FATAL] No edges extracted. Check for internal references and extraction logic.', log_lines)
            sys.exit(11)
        # Log sample data for debugging
        log(f'[DEBUG] Sample node: {json.dumps(nodes[0], ensure_ascii=False) if nodes else "<none>"}', log_lines)
        log(f'[DEBUG] Sample edge: {json.dumps(edges[0], ensure_ascii=False) if edges else "<none>"}', log_lines)
        # Step 4: Output JSON and split into chunks
        out_json = {
            'nodes': nodes,
            'edges': edges,
            'meta': {
                'generated_at': now_est.isoformat(),
                'source_path': source_path,
                'file_count': len(kt_files),
                'node_count': len(nodes),
                'edge_count': len(edges)
            }
        }
        out_path = os.path.join(output_dir, 'dependency-map.json')
        chunk_size = 5000
        node_chunks = [nodes[i:i+chunk_size] for i in range(0, len(nodes), chunk_size)]
        edge_chunks = [edges[i:i+chunk_size] for i in range(0, len(edges), chunk_size)]
        node_chunk_files = []
        edge_chunk_files = []
        # Debug: log full chunk data for first chunk
        if node_chunks:
            log(f'[DEBUG] Full node chunk 1 data: {json.dumps(node_chunks[0], ensure_ascii=False) if node_chunks[0] else "<none>"}', log_lines)
        if edge_chunks:
            log(f'[DEBUG] Full edge chunk 1 data: {json.dumps(edge_chunks[0], ensure_ascii=False) if edge_chunks[0] else "<none>"}', log_lines)
        # Explicitly check chunk slicing logic
        log(f'[DEBUG] node_chunks count: {len(node_chunks)}, edge_chunks count: {len(edge_chunks)}', log_lines)
        for idx, chunk in enumerate(node_chunks):
            fname = f'nodes-{idx+1}.json'
            node_chunk_files.append(fname)
            log(f'[DEBUG] Writing node chunk {idx+1} with {len(chunk)} nodes', log_lines)
            if chunk:
                log(f'[DEBUG] Sample node in chunk {idx+1}: {json.dumps(chunk[0], ensure_ascii=False) if chunk else "<none>"}', log_lines)
                with open(os.path.join(output_dir, fname), 'w', encoding='utf-8') as f:
                    json.dump({"nodes": chunk}, f, indent=2)
            else:
                log(f'[FATAL] Node chunk {idx+1} is empty. Aborting.', log_lines)
                sys.exit(12)
        for idx, chunk in enumerate(edge_chunks):
            fname = f'edges-{idx+1}.json'
            edge_chunk_files.append(fname)
            log(f'[DEBUG] Writing edge chunk {idx+1} with {len(chunk)} edges', log_lines)
            if chunk:
                log(f'[DEBUG] Sample edge in chunk {idx+1}: {json.dumps(chunk[0], ensure_ascii=False) if chunk else "<none>"}', log_lines)
                with open(os.path.join(output_dir, fname), 'w', encoding='utf-8') as f:
                    json.dump({"edges": chunk}, f, indent=2)
            else:
                log(f'[FATAL] Edge chunk {idx+1} is empty. Aborting.', log_lines)
                sys.exit(13)
        manifest = {
            'nodeChunks': node_chunk_files,
            'edgeChunks': edge_chunk_files,
            'meta': out_json['meta']
        }
        with open(os.path.join(output_dir, 'graph-chunks.json'), 'w', encoding='utf-8') as f:
            json.dump(manifest, f, indent=2)
        # Also write the full JSON for reference
        with open(out_path, 'w', encoding='utf-8') as f:
            json.dump(out_json, f, indent=2)
        log(f'[INFO] Graph data written to {out_path} and chunked files', log_lines)
        # Step 5: Generate HTML visualization from template (chunked loading)
        html_path = os.path.join(output_dir, 'dependency-graph.html')
        template_path = os.path.join(os.path.dirname(__file__), 'dependency-graph-template.html')
        try:
            with open(template_path, 'r', encoding='utf-8') as tpl:
                html_template = tpl.read()
            # Replace fetch logic with chunked loader
            html_template = html_template.replace(
                "fetch('dependency-map.json')\n  .then(r => r.json())\n  .then(data => {",
                "fetch('graph-chunks.json')\n  .then(r => r.json())\n  .then(manifest => {\n    const nodePromises = manifest.nodeChunks.map(f => fetch(f).then(r => r.json()));\n    const edgePromises = manifest.edgeChunks.map(f => fetch(f).then(r => r.json()));\n    Promise.all([...nodePromises, ...edgePromises]).then(chunks => {\n      const nodes = [].concat(...chunks.slice(0, manifest.nodeChunks.length));\n      const edges = [].concat(...chunks.slice(manifest.nodeChunks.length));\n      const data = { nodes, edges, meta: manifest.meta };\n"
            )
            # Add heading to HTML for basic visualization check
            html_template = html_template.replace('<body>', "<body>\n<h1 style='text-align:center;'>Meshrabiya Dependency Graph Visualization</h1>")
            with open(html_path, 'w', encoding='utf-8') as f:
                f.write(html_template)
            log(f'[INFO] Visualization written to {html_path} (chunked loader for local viewing)', log_lines)
            # Add user instructions for viewing
            log('[INFO] To view the visualization:', log_lines)
            log(f'  cd {output_dir}', log_lines)
            log('  python3 -m http.server 8080', log_lines)
            log(f'  Open http://localhost:8080/dependency-graph.html in your browser', log_lines)
        except Exception as e:
            log(f'[ERROR] Failed to write HTML visualization: {e}', log_lines)
            sys.exit(4)
        log('[DEBUG] main() complete', log_lines)
    except Exception as e:
        log(f'[FATAL] Uncaught error in main: {e}', log_lines)
        sys.exit(99)

if __name__ == '__main__':
    main()
