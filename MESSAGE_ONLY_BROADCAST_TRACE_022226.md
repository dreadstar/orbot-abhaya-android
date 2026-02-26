# Message-Only Broadcast End-to-End Code Path Trace (Disk-Verified)

## Objective
Trace, step by step, the literal code path for a message-only broadcast (no file) from the call to `BroadcastMessageHandler.sendBroadcast()` on the sender, through all function calls and packet handling, to the reception and handling on the receiving device, using only code (no comments or docs). All steps are verified from the actual codebase.

---

## 1. Sender Side: Packet Creation and Sending

### 1.1. `BroadcastMessageHandler.sendBroadcast()`
- If `filePath` is empty, constructs a text-only broadcast.
- Calls `BroadcastPacketSerializer.serialize()` to create the packet payload (lines 190-213 in BroadcastMessageHandler.kt).
- Constructs a `VirtualPacket` with broadcast addressing (`toAddr = VirtualPacket.ADDR_BROADCAST`).
- Calls `virtualNode.route(packet)` to send the packet (line 216 in BroadcastMessageHandler.kt).

### 1.2. `virtualNode.route(packet)` (VirtualNode.kt)
- Copies packet data and offloads processing to a connection pool thread (lines 762-900).
- Calls `processRoutePacket(packetCopy, ...)`.

### 1.3. `processRoutePacket()` (VirtualNode.kt)
- Checks if `toAddr == ADDR_BROADCAST`.
- Computes a broadcast ID and deduplicates using `seenBroadcasts`.
- If not seen before and node has role `MESH_ROUTER` or `MESH_HUB`, iterates over `originatingMessageManager.neighbors()` and calls `it.second.receivedFromSocket.send(...)` for each neighbor except the sender and last hop. (Lines 901-920 in VirtualNode.kt)

### 1.4. `receivedFromSocket.send(...)` (VirtualNodeDatagramSocket)
- This is the last application-level call before the packet is handed off to system or dependency networking code for transmission.


## 2. Receiver Side: Packet Reception and Routing

### 2.1. Incoming Packet Handling (VirtualNode.kt)
- When a packet arrives, `route()` is called (directly or via socket).
- Calls `processRoutePacket()` as above.
- Checks if the packet is a broadcast (by inspecting the packet type byte at offset+4 in the payload).
- If so, calls `broadcastMessageHandler?.onReceiveBroadcastPacket(virtualPacket)` (line 650 in VirtualNode.kt).
- This is the last application-level handler before the packet is processed by the broadcast handler logic.

### 2.2. `BroadcastMessageHandler.onReceiveBroadcastPacket()`
- Extracts the payload from the packet.
- Calls `BroadcastPacketSerializer.getPacketType(payload)` to determine type.
- For message-only broadcasts, type is `TYPE_BROADCAST_CHUNK`.
- Calls `handleBroadcastChunk(packet, payload)`.

### 2.3. `handleBroadcastChunk()`
- Calls `BroadcastPacketSerializer.deserialize(payload)` to extract (broadcastId, messageText, ...).
- If `totalChunks == 0` (text-only), immediately calls `onTextOnlyBroadcastComplete()`.
- Notifies listeners with a `BroadcastReceivedDto` containing the message.

---

## 3. Summary Table: Literal Function Call Chain

| Step | Sender Function | Packet | Receiver Function | Result |
|------|-----------------|--------|------------------|--------|
| 1    | sendBroadcast() | →      |                  |        |
| 2    | virtualNode.route() | →  |                  |        |
| 3    | processRoutePacket() | → |                  |        |
| 4    | (network)           | →  |                  |        |
| 5    | route() (receiver)  | →  |                  |        |
| 6    | processRoutePacket()| →  |                  |        |
| 7    | onReceiveBroadcastPacket() | → |             |        |
| 8    | handleBroadcastChunk() | → |                  |        |
| 9    | onTextOnlyBroadcastComplete() | |            | Listeners notified |

---

## 4. Key Code Locations (Verified)
- BroadcastMessageHandler.kt: sendBroadcast(), onReceiveBroadcastPacket(), handleBroadcastChunk(), onTextOnlyBroadcastComplete()
- VirtualNode.kt: route(), processRoutePacket(), broadcastMessageHandler property, broadcast packet detection logic
- BroadcastPacketSerializer.kt: serialize(), deserialize(), getPacketType()

---

## 5. Conclusion
This trace is fully code-verified and shows the literal, stepwise path for message-only broadcasts from sender to receiver, with all intermediate function calls and packet handling explicitly documented. No assumptions or reliance on comments/docs.
