import express from 'express';
import { createServer } from 'node:http';
import { Server } from 'socket.io';
const app = express();
app.disable('x-powered-by');
app.use(express.static('public'));
const httpServer = createServer(app);
const io = new Server(httpServer, { cors: { origin: false }, maxHttpBufferSize: 100000 });
const sessions = new Map();
const validId = x => typeof x === 'string' && /^[a-f0-9]{48}$/.test(x);
function leave(socket) {
  const id = socket.data.sessionId;
  if (!id) return;
  const s = sessions.get(id);
  if (!s) return;
  if (s.broadcaster === socket.id) { io.to(id).emit('session-ended'); sessions.delete(id); }
  else if (s.viewer === socket.id) { s.viewer = null; io.to(s.broadcaster).emit('viewer-left'); }
  socket.leave(id);
  socket.data.sessionId = null;
}
io.on('connection', socket => {
  socket.on('join', payload => {
    if (!payload || !validId(payload.sessionId) || !['broadcaster','viewer'].includes(payload.role)) return socket.emit('session-error', 'Invalid session');
    leave(socket);
    const {sessionId:id, role} = payload;
    let s = sessions.get(id);
    if (role === 'broadcaster') {
      if (s) return socket.emit('session-error','Session already exists');
      s = {broadcaster:socket.id, viewer:null}; sessions.set(id,s);
    } else {
      if (!s || s.viewer) return socket.emit('session-error','Session unavailable');
      s.viewer = socket.id;
    }
    socket.join(id); socket.data.sessionId = id; socket.data.role = role;
    socket.emit('joined', {role});
    if (role === 'viewer') io.to(s.broadcaster).emit('viewer-joined');
  });
  for (const event of ['offer','answer','ice']) socket.on(event, payload => {
    const id = socket.data.sessionId; const s = sessions.get(id);
    if (!s || !payload || typeof payload !== 'object') return;
    if (event === 'offer' && socket.id === s.broadcaster && s.viewer) io.to(s.viewer).emit(event,payload);
    if (event === 'answer' && socket.id === s.viewer) io.to(s.broadcaster).emit(event,payload);
    if (event === 'ice') {
      const target = socket.id === s.broadcaster ? s.viewer : socket.id === s.viewer ? s.broadcaster : null;
      if (target) io.to(target).emit(event,payload);
    }
  });
  socket.on('disconnect', () => leave(socket));
});
app.get('/health', (_req,res)=>res.json({status:'ok'}));
httpServer.listen(Number(process.env.PORT || 3000), '0.0.0.0');
