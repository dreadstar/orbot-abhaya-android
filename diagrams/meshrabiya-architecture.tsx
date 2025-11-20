/**
 * Meshrabiya Architecture Diagram
 * 
 * Shows canonical workflow architecture with:
 * - VirtualNode as root dependency container
 * - CoreGossipBroadcastService instantiation pattern
 * - Client/Server separation in storage and compute
 * - Replica count propagation model
 * - Message routing through MeshEcosystemListener
 */

import React from 'react';

const MeshrabiyaArchitecture: React.FC = () => {
  return (
    <svg width="1400" height="1000" xmlns="http://www.w3.org/2000/svg">
      <defs>
        <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
          <polygon points="0 0, 10 3.5, 0 7" fill="#333" />
        </marker>
        <marker id="arrowhead-red" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
          <polygon points="0 0, 10 3.5, 0 7" fill="#d32f2f" />
        </marker>
        <marker id="arrowhead-green" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
          <polygon points="0 0, 10 3.5, 0 7" fill="#388e3c" />
        </marker>
        <marker id="arrowhead-blue" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
          <polygon points="0 0, 10 3.5, 0 7" fill="#1976d2" />
        </marker>
      </defs>
      
      {/* Title */}
      <text x="700" y="30" fontSize="24" fontWeight="bold" textAnchor="middle" fill="#333">
        Meshrabiya Canonical Architecture
      </text>
      <text x="700" y="55" fontSize="14" textAnchor="middle" fill="#666">
        Storage and Compute Workflow Model
      </text>

      {/* Legend */}
      <g transform="translate(20, 80)">
        <text x="0" y="0" fontSize="12" fontWeight="bold" fill="#333">Legend:</text>
        <line x1="0" y1="10" x2="40" y2="10" stroke="#1976d2" strokeWidth="2" markerEnd="url(#arrowhead-blue)" />
        <text x="45" y="14" fontSize="11" fill="#666">Client Request Flow</text>
        
        <line x1="0" y1="30" x2="40" y2="30" stroke="#388e3c" strokeWidth="2" markerEnd="url(#arrowhead-green)" />
        <text x="45" y="34" fontSize="11" fill="#666">Storage/Compute Flow</text>
        
        <line x1="0" y1="50" x2="40" y2="50" stroke="#d32f2f" strokeWidth="2" markerEnd="url(#arrowhead-red)" />
        <text x="45" y="54" fontSize="11" fill="#666">Replication Flow</text>
        
        <line x1="0" y1="70" x2="40" y2="70" stroke="#333" strokeWidth="2" strokeDasharray="5,5" />
        <text x="45" y="74" fontSize="11" fill="#666">Dependency Access</text>
      </g>

      {/* VirtualNode - Root Container */}
      <g transform="translate(550, 150)">
        <rect x="0" y="0" width="300" height="100" fill="#e3f2fd" stroke="#1976d2" strokeWidth="2" rx="5" />
        <text x="150" y="25" fontSize="16" fontWeight="bold" textAnchor="middle" fill="#1976d2">VirtualNode</text>
        <text x="150" y="45" fontSize="12" textAnchor="middle" fill="#333">(Root Dependency Container)</text>
        <text x="10" y="65" fontSize="11" fill="#555">• meshGossipService</text>
        <text x="10" y="80" fontSize="11" fill="#555">• coreGossipBroadcastService</text>
        <text x="10" y="95" fontSize="11" fill="#555">• Instantiated in constructor</text>
      </g>

      {/* CoreGossipBroadcastService */}
      <g transform="translate(550, 280)">
        <rect x="0" y="0" width="300" height="80" fill="#fff3e0" stroke="#f57c00" strokeWidth="2" rx="5" />
        <text x="150" y="25" fontSize="14" fontWeight="bold" textAnchor="middle" fill="#f57c00">CoreGossipBroadcastService</text>
        <text x="150" y="45" fontSize="11" textAnchor="middle" fill="#333">(Mesh-wide Broadcast Abstraction)</text>
        <text x="10" y="60" fontSize="10" fill="#555">• sendStorageNodeRequest()</text>
        <text x="10" y="73" fontSize="10" fill="#555">• sendComputeTaskRequest()</text>
      </g>

      {/* MeshGossipService */}
      <g transform="translate(950, 200)">
        <rect x="0" y="0" width="200" height="60" fill="#f5f5f5" stroke="#666" strokeWidth="1" rx="5" />
        <text x="100" y="25" fontSize="12" fontWeight="bold" textAnchor="middle" fill="#333">MeshGossipService</text>
        <text x="100" y="40" fontSize="10" textAnchor="middle" fill="#555">(UDP Transport Layer)</text>
        <text x="100" y="53" fontSize="9" textAnchor="middle" fill="#999">Low-level broadcasts</text>
      </g>

      {/* Storage Layer */}
      <g transform="translate(50, 420)">
        <rect x="0" y="0" width="550" height="250" fill="#e8f5e9" stroke="#388e3c" strokeWidth="2" rx="5" />
        <text x="275" y="25" fontSize="16" fontWeight="bold" textAnchor="middle" fill="#388e3c">Storage Layer</text>
        
        {/* Client Side */}
        <g transform="translate(20, 50)">
          <rect x="0" y="0" width="240" height="180" fill="#ffffff" stroke="#1976d2" strokeWidth="1" rx="3" />
          <text x="120" y="20" fontSize="13" fontWeight="bold" textAnchor="middle" fill="#1976d2">Client Side</text>
          <text x="5" y="40" fontSize="11" fontWeight="bold" fill="#333">DistributedStorageManager</text>
          <text x="10" y="60" fontSize="10" fill="#555">storeFile()</text>
          <text x="15" y="75" fontSize="9" fill="#666">• Create chunks (count=0)</text>
          <text x="15" y="88" fontSize="9" fill="#666">• Encrypt & hash</text>
          <text x="15" y="101" fontSize="9" fill="#666">• Broadcast via CGBS</text>
          
          <text x="10" y="125" fontSize="10" fill="#555">retrieveFile()</text>
          <text x="15" y="140" fontSize="9" fill="#666">• Request chunks</text>
          <text x="15" y="153" fontSize="9" fill="#666">• Decrypt & reassemble</text>
          
          <text x="10" y="175" fontSize="10" fill="#555">updateFileAccess()</text>
        </g>
        
        {/* Server Side */}
        <g transform="translate(280, 50)">
          <rect x="0" y="0" width="250" height="180" fill="#ffffff" stroke="#388e3c" strokeWidth="1" rx="3" />
          <text x="125" y="20" fontSize="13" fontWeight="bold" textAnchor="middle" fill="#388e3c">Storage Node Side</text>
          <text x="5" y="40" fontSize="11" fontWeight="bold" fill="#333">DistributedStorageManager</text>
          <text x="10" y="60" fontSize="10" fill="#555">handleIncomingChunkStorage()</text>
          <text x="15" y="75" fontSize="9" fill="#666">• Verify permissions</text>
          <text x="15" y="88" fontSize="9" fill="#666">• Store chunk (count=N)</text>
          <text x="15" y="101" fontSize="9" fill="#666">• Index metadata</text>
          <text x="15" y="114" fontSize="9" fill="#666">• Initiate replication</text>
          
          <text x="10" y="135" fontSize="10" fill="#555">initiateChunkReplication()</text>
          <text x="15" y="150" fontSize="9" fill="#666">• Check count < target</text>
          <text x="15" y="163" fontSize="9" fill="#666">• Broadcast (count=N+1)</text>
          <text x="15" y="176" fontSize="9" fill="#666">• Act as client</text>
        </g>
      </g>

      {/* Compute Layer */}
      <g transform="translate(650, 420)">
        <rect x="0" y="0" width="550" height="250" fill="#fce4ec" stroke="#c2185b" strokeWidth="2" rx="5" />
        <text x="275" y="25" fontSize="16" fontWeight="bold" textAnchor="middle" fill="#c2185b">Compute Layer</text>
        
        {/* Client Side */}
        <g transform="translate(20, 50)">
          <rect x="0" y="0" width="240" height="180" fill="#ffffff" stroke="#1976d2" strokeWidth="1" rx="3" />
          <text x="120" y="20" fontSize="13" fontWeight="bold" textAnchor="middle" fill="#1976d2">Client Side</text>
          <text x="5" y="40" fontSize="11" fontWeight="bold" fill="#333">IntelligentDistributedComputeService</text>
          <text x="10" y="60" fontSize="10" fill="#555">processTaskRequest()</text>
          <text x="15" y="75" fontSize="9" fill="#666">• Create task request</text>
          <text x="15" y="88" fontSize="9" fill="#666">• Track status (PENDING)</text>
          <text x="15" y="101" fontSize="9" fill="#666">• Broadcast via CGBS</text>
          <text x="15" y="114" fontSize="9" fill="#666">• Wait for responses</text>
          
          <text x="10" y="135" fontSize="10" fill="#555">handleComputeNodeResponse()</text>
          <text x="15" y="150" fontSize="9" fill="#666">• Update task status</text>
          <text x="15" y="163" fontSize="9" fill="#666">• Store results</text>
          <text x="15" y="176" fontSize="9" fill="#666">• Notify callbacks</text>
        </g>
        
        {/* Server Side */}
        <g transform="translate(280, 50)">
          <rect x="0" y="0" width="250" height="180" fill="#ffffff" stroke="#c2185b" strokeWidth="1" rx="3" />
          <text x="125" y="20" fontSize="13" fontWeight="bold" textAnchor="middle" fill="#c2185b">Compute Node Side</text>
          <text x="5" y="40" fontSize="11" fontWeight="bold" fill="#333">IntelligentDistributedComputeService</text>
          <text x="10" y="60" fontSize="10" fill="#555">handleIncomingTaskRequest()</text>
          <text x="15" y="75" fontSize="9" fill="#666">• Validate capabilities</text>
          <text x="15" y="88" fontSize="9" fill="#666">• Check resource availability</text>
          <text x="15" y="101" fontSize="9" fill="#666">• Execute task</text>
          <text x="15" y="114" fontSize="9" fill="#666">• Store result as file</text>
          
          <text x="10" y="135" fontSize="10" fill="#555">sendTaskCompletion()</text>
          <text x="15" y="150" fontSize="9" fill="#666">• Create result metadata</text>
          <text x="15" y="163" fontSize="9" fill="#666">• Direct message to client</text>
          <text x="15" y="176" fontSize="9" fill="#666">• Broadcast completion</text>
        </g>
      </g>

      {/* MeshEcosystemListener */}
      <g transform="translate(250, 720)">
        <rect x="0" y="0" width="900" height="120" fill="#fff9c4" stroke="#f9a825" strokeWidth="2" rx="5" />
        <text x="450" y="25" fontSize="14" fontWeight="bold" textAnchor="middle" fill="#f9a825">MeshEcosystemListener</text>
        <text x="450" y="45" fontSize="11" textAnchor="middle" fill="#333">(Message Routing Layer)</text>
        
        <g transform="translate(20, 60)">
          <text x="0" y="0" fontSize="10" fill="#555">onStorageNodeRequest() → DistributedStorageManager.handleIncomingChunkStorage()</text>
          <text x="0" y="18" fontSize="10" fill="#555">onChunkTransfer() → DistributedStorageManager.handleIncomingChunkStorage()</text>
          <text x="0" y="36" fontSize="10" fill="#555">onComputeTaskRequest() → IntelligentDistributedComputeService.handleIncomingTaskRequest()</text>
          <text x="0" y="54" fontSize="10" fill="#555">onAccessUpdateNotice() → DistributedStorageManager.handleAccessUpdate()</text>
        </g>
      </g>

      {/* Replica Count Flow Diagram */}
      <g transform="translate(50, 880)">
        <text x="250" y="0" fontSize="14" fontWeight="bold" fill="#d32f2f">Replica Count Propagation Model</text>
        
        <g transform="translate(0, 20)">
          {/* Client */}
          <circle cx="30" cy="30" r="25" fill="#e3f2fd" stroke="#1976d2" strokeWidth="2" />
          <text x="30" y="28" fontSize="10" textAnchor="middle" fill="#333">Client</text>
          <text x="30" y="38" fontSize="9" textAnchor="middle" fill="#d32f2f">(0)</text>
          
          {/* Arrow to Storage A */}
          <line x1="55" y1="30" x2="125" y2="30" stroke="#1976d2" strokeWidth="2" markerEnd="url(#arrowhead-blue)" />
          <text x="90" y="25" fontSize="9" fill="#1976d2">count=0</text>
          
          {/* Storage A */}
          <circle cx="150" cy="30" r="25" fill="#e8f5e9" stroke="#388e3c" strokeWidth="2" />
          <text x="150" y="25" fontSize="9" textAnchor="middle" fill="#333">Storage A</text>
          <text x="150" y="35" fontSize="8" textAnchor="middle" fill="#d32f2f">store(0)</text>
          
          {/* Arrow to Storage B */}
          <line x1="175" y1="30" x2="245" y2="30" stroke="#d32f2f" strokeWidth="2" markerEnd="url(#arrowhead-red)" />
          <text x="210" y="25" fontSize="9" fill="#d32f2f">count=1</text>
          
          {/* Storage B */}
          <circle cx="270" cy="30" r="25" fill="#e8f5e9" stroke="#388e3c" strokeWidth="2" />
          <text x="270" y="25" fontSize="9" textAnchor="middle" fill="#333">Storage B</text>
          <text x="270" y="35" fontSize="8" textAnchor="middle" fill="#d32f2f">store(1)</text>
          
          {/* Arrow to Storage C */}
          <line x1="295" y1="30" x2="365" y2="30" stroke="#d32f2f" strokeWidth="2" markerEnd="url(#arrowhead-red)" />
          <text x="330" y="25" fontSize="9" fill="#d32f2f">count=2</text>
          
          {/* Storage C */}
          <circle cx="390" cy="30" r="25" fill="#e8f5e9" stroke="#388e3c" strokeWidth="2" />
          <text x="390" y="25" fontSize="9" textAnchor="middle" fill="#333">Storage C</text>
          <text x="390" y="35" fontSize="8" textAnchor="middle" fill="#d32f2f">store(2)</text>
          
          {/* Arrow to Storage D */}
          <line x1="415" y1="30" x2="485" y2="30" stroke="#d32f2f" strokeWidth="2" markerEnd="url(#arrowhead-red)" />
          <text x="450" y="25" fontSize="9" fill="#d32f2f">count=3</text>
          
          {/* Storage D */}
          <circle cx="510" cy="30" r="25" fill="#ffebee" stroke="#d32f2f" strokeWidth="2" />
          <text x="510" y="25" fontSize="9" textAnchor="middle" fill="#333">Storage D</text>
          <text x="510" y="35" fontSize="8" textAnchor="middle" fill="#d32f2f">STOP(3≥3)</text>
        </g>
        
        <text x="250" y="90" fontSize="10" textAnchor="middle" fill="#666">
          Each node receives count N, stores with N, replicates with N+1
        </text>
      </g>

      {/* Arrows showing dependencies */}
      {/* VirtualNode to CoreGossipBroadcastService */}
      <line x1="700" y1="250" x2="700" y2="280" stroke="#333" strokeWidth="2" markerEnd="url(#arrowhead)" />
      
      {/* VirtualNode to MeshGossipService */}
      <line x1="850" y1="200" x2="950" y2="220" stroke="#333" strokeWidth="1" strokeDasharray="5,5" />
      
      {/* CoreGossipBroadcastService to Storage Layer */}
      <line x1="620" y1="360" x2="300" y2="420" stroke="#1976d2" strokeWidth="2" markerEnd="url(#arrowhead-blue)" />
      
      {/* CoreGossipBroadcastService to Compute Layer */}
      <line x1="780" y1="360" x2="900" y2="420" stroke="#1976d2" strokeWidth="2" markerEnd="url(#arrowhead-blue)" />
      
      {/* Storage to MeshEcosystemListener */}
      <line x1="325" y1="670" x2="500" y2="720" stroke="#388e3c" strokeWidth="2" markerEnd="url(#arrowhead-green)" />
      
      {/* Compute to MeshEcosystemListener */}
      <line x1="925" y1="670" x2="800" y2="720" stroke="#c2185b" strokeWidth="2" markerEnd="url(#arrowhead-red)" />

      {/* Documentation References */}
      <g transform="translate(1220, 150)">
        <rect x="0" y="0" width="160" height="160" fill="#f5f5f5" stroke="#999" strokeWidth="1" rx="5" />
        <text x="80" y="20" fontSize="12" fontWeight="bold" textAnchor="middle" fill="#333">Documentation</text>
        
        <text x="10" y="45" fontSize="10" fontWeight="bold" fill="#1976d2">CANONICAL_</text>
        <text x="10" y="57" fontSize="10" fontWeight="bold" fill="#1976d2">WORKFLOWS.md</text>
        <text x="10" y="72" fontSize="8" fill="#666">7 complete workflows</text>
        <text x="10" y="84" fontSize="8" fill="#666">Client/Server patterns</text>
        
        <text x="10" y="105" fontSize="10" fontWeight="bold" fill="#388e3c">REQUIRED_WORKFLOW_</text>
        <text x="10" y="117" fontSize="10" fontWeight="bold" fill="#388e3c">CHANGES.md</text>
        <text x="10" y="132" fontSize="8" fill="#666">5 specific issues</text>
        <text x="10" y="144" fontSize="8" fill="#666">Implementation plan</text>
      </g>

      {/* Architectural Decisions Box */}
      <g transform="translate(1220, 330)">
        <rect x="0" y="0" width="160" height="180" fill="#fff3e0" stroke="#f57c00" strokeWidth="2" rx="5" />
        <text x="80" y="20" fontSize="11" fontWeight="bold" textAnchor="middle" fill="#f57c00">Key Decisions</text>
        
        <text x="10" y="40" fontSize="9" fontWeight="bold" fill="#333">1. Replica Count:</text>
        <text x="15" y="52" fontSize="8" fill="#666">Start at 0</text>
        <text x="15" y="62" fontSize="8" fill="#666">Increment during</text>
        <text x="15" y="72" fontSize="8" fill="#666">transfer (not after)</text>
        
        <text x="10" y="90" fontSize="9" fontWeight="bold" fill="#333">2. No Sync:</text>
        <text x="15" y="102" fontSize="8" fill="#666">Trust propagation</text>
        <text x="15" y="112" fontSize="8" fill="#666">Independent counts</text>
        
        <text x="10" y="130" fontSize="9" fontWeight="bold" fill="#333">3. Retry Strategy:</text>
        <text x="15" y="142" fontSize="8" fill="#666">Mirror storage</text>
        <text x="15" y="152" fontSize="8" fill="#666">request pattern</text>
        
        <text x="10" y="170" fontSize="9" fontWeight="bold" fill="#333">4. CGBS Access:</text>
        <text x="15" y="180" fontSize="8" fill="#666">Via VirtualNode</text>
      </g>
    </svg>
  );
};

export default MeshrabiyaArchitecture;
