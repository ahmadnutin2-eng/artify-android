'use strict';

const http = require('http');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

const port = Number(process.env.PORT || 8787);
const waiting = [];
const clients = new Map();
const sessions = new Map();

const server = http.createServer((request, response) => {
  if (request.url === '/health') {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ ok: true, waiting: waiting.length, rooms: sessions.size }));
    return;
  }
  response.writeHead(404).end();
});

const wss = new WebSocketServer({ server, maxPayload: 10 * 1024 * 1024 });

function send(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}

function removeFromQueue(ws) {
  let index;
  while ((index = waiting.indexOf(ws)) !== -1) waiting.splice(index, 1);
}

function leaveRoom(ws) {
  const client = clients.get(ws);
  const sessionId = client && client.sessionId;
  if (!sessionId) return;
  const room = sessions.get(sessionId);
  if (room) {
    const partner = room.a === ws ? room.b : room.a;
    send(partner, { type: 'partner_left', participantName: client.name });
    const partnerClient = clients.get(partner);
    if (partnerClient) partnerClient.sessionId = null;
    sessions.delete(sessionId);
  }
  client.sessionId = null;
}

function matchAvailable() {
  while (waiting.length >= 2) {
    const a = waiting.shift();
    if (!a || a.readyState !== WebSocket.OPEN) continue;
    const aClient = clients.get(a);
    if (!aClient || aClient.sessionId) continue;

    let bIndex = waiting.findIndex(candidate => {
      const c = clients.get(candidate);
      return candidate.readyState === WebSocket.OPEN && c && !c.sessionId && c.id !== aClient.id;
    });
    if (bIndex < 0) {
      waiting.unshift(a);
      return;
    }
    const b = waiting.splice(bIndex, 1)[0];
    const bClient = clients.get(b);
    const sessionId = crypto.randomUUID();
    // The artist waiting longest defines the shared sheet. Both clients receive exactly the same
    // dimensions before CanvasActivity starts.
    const width = Math.max(256, Math.min(4096, aClient.width));
    const height = Math.max(256, Math.min(4096, aClient.height));
    aClient.sessionId = sessionId;
    bClient.sessionId = sessionId;
    sessions.set(sessionId, { a, b });
    send(a, {
      type: 'matched', sessionId, partnerId: bClient.id, partnerName: bClient.name, width, height
    });
    send(b, {
      type: 'matched', sessionId, partnerId: aClient.id, partnerName: aClient.name, width, height
    });
  }
}

function relay(ws, message) {
  const client = clients.get(ws);
  if (!client || !client.sessionId || message.sessionId !== client.sessionId) return;
  const room = sessions.get(client.sessionId);
  if (!room) return;
  const partner = room.a === ws ? room.b : room.a;
  const forwarded = {
    ...message,
    participantId: client.id,
    participantName: client.name
  };
  // Prevent an unexpectedly large base64 allocation from one bad client monopolising the relay.
  if (forwarded.type === 'patch' && String(forwarded.png || '').length > 9_000_000) {
    send(ws, { type: 'error', message: 'Stroke patch is too large' });
    return;
  }
  send(partner, forwarded);
}

wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', raw => {
    let message;
    try {
      message = JSON.parse(raw.toString());
    } catch (_) {
      send(ws, { type: 'error', message: 'Malformed JSON' });
      return;
    }
    if (message.type === 'search') {
      leaveRoom(ws);
      removeFromQueue(ws);
      const id = String(message.clientId || '').slice(0, 80);
      if (!id) return send(ws, { type: 'error', message: 'Missing client id' });
      clients.set(ws, {
        id,
        name: String(message.name || 'Artist').trim().slice(0, 40) || 'Artist',
        width: Number(message.width) || 2048,
        height: Number(message.height) || 2048,
        sessionId: null
      });
      waiting.push(ws);
      matchAvailable();
      return;
    }
    if (message.type === 'stroke' || message.type === 'patch') return relay(ws, message);
    if (message.type === 'leave') return leaveRoom(ws);
    if (message.type === 'cancel') return removeFromQueue(ws);
  });

  ws.on('close', () => {
    removeFromQueue(ws);
    leaveRoom(ws);
    clients.delete(ws);
  });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 30_000);

wss.on('close', () => clearInterval(heartbeat));
server.listen(port, () => console.log(`Artify collaboration relay listening on :${port}`));
