# A few servers in the list, so the server list has rows (the first one is the test server).
python3 - <<'PY'
import struct
def s(t): b = t.encode(); return struct.pack('>H', len(b)) + b
def tag_str(name, val): return b'\x08' + s(name) + s(val)
servers = [("Catgirl Island", "localhost"), ("NexusMC", "play.nexusmc.invalid"), ("Lilac Valley", "lilac.invalid")]
body = b'\x09' + s("servers") + b'\x0a' + struct.pack('>i', len(servers))
for name, ip in servers:
    body += tag_str("name", name) + tag_str("ip", ip) + b'\x00'
open('servers.dat', 'wb').write(b'\x0a' + s("") + body + b'\x00')
PY
