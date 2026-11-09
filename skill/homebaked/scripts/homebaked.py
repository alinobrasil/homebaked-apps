#!/usr/bin/env python3
"""
Homebaked: link an Android phone and push mini-apps to it over Wi-Fi.

Python 3.8+ standard library only. Claude runs this; people don't have to.

    homebaked.py pair [--open]          show a QR code for the phone to scan (step 1)
    homebaked.py pair --wait            wait until the phone has linked (step 2)
    homebaked.py status                 linked phones, and whether each can be reached now
    homebaked.py push <dir> [file ...]  send a whole app folder, or just some files of it
    homebaked.py list                   apps on the phone
    homebaked.py delete <app>           remove an app (and its saved data) from the phone
    homebaked.py unlink [phone]         forget a phone on this computer

Add --phone <name or id> to pick a phone when more than one is linked.

Linked phones and their keys are kept in phones.json in the config folder
(~/.config/homebaked, or %APPDATA%\\Homebaked on Windows), readable only by you.
"""

import base64
import hashlib
import hmac
import http.server
import io
import json
import os
import re
import secrets
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

# ---- Brand: keep in step with android/gradle.properties -------------------------------

NAME = "Homebaked"
SCHEME = "homebaked"
PROTOCOL = 1
SERVICE = "_%s._tcp.local" % SCHEME[:14]
QR_LIFETIME = 300          # seconds a link QR code stays valid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import qr  # noqa: E402


class Fail(Exception):
    """A problem to report to the person, in plain words."""


def out(msg=""):
    try:
        print(msg, flush=True)
    except UnicodeEncodeError:  # an old Windows console
        print(msg.encode("ascii", "replace").decode(), flush=True)


# ---- Config ---------------------------------------------------------------------------

def config_dir():
    if os.name == "nt":
        base = os.environ.get("APPDATA") or os.path.expanduser("~")
        d = os.path.join(base, NAME)
    else:
        d = os.path.join(os.environ.get("XDG_CONFIG_HOME") or os.path.expanduser("~/.config"), SCHEME)
    os.makedirs(d, exist_ok=True)
    return d


def _path(name):
    return os.path.join(config_dir(), name)


def _write_private(path, data):
    """Keys live in here, so the file is created readable by this user only."""
    tmp = path + ".tmp"
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def _read(path, default):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def load_phones():
    return _read(_path("phones.json"), {"phones": {}, "default": None})


def save_phones(cfg):
    _write_private(_path("phones.json"), cfg)


def pick_phone(cfg, wanted=None):
    phones = cfg["phones"]
    if not phones:
        raise Fail("No phone is linked yet. Run: homebaked.py pair")
    if wanted:
        w = wanted.lower()
        hits = [pid for pid, p in phones.items() if w in pid or w in p["name"].lower()]
        if len(hits) != 1:
            raise Fail("No single linked phone matches %r. Linked: %s"
                       % (wanted, ", ".join(p["name"] for p in phones.values())))
        return hits[0], phones[hits[0]]
    pid = cfg.get("default") if cfg.get("default") in phones else next(iter(phones))
    return pid, phones[pid]


def sanitize(raw):
    """Same rule as the phone: app ids are DNS labels. "Tip_Calc" -> "tip-calc"."""
    s = re.sub(r"[^a-z0-9-]+", "-", raw.strip().lower())
    s = re.sub(r"-{2,}", "-", s).strip("-")
    return s[:63].rstrip("-")


# ---- Network helpers ------------------------------------------------------------------

def _is_private(ip):
    try:
        a, b = [int(x) for x in ip.split(".")[:2]]
    except ValueError:
        return False
    return (a == 10 or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168)
            or (a == 100 and 64 <= b <= 127))


def local_ipv4s():
    """This computer's LAN (and Tailscale) addresses, the one with the default route first."""
    found = []
    for probe in ("192.168.0.1", "10.255.255.255", "8.8.8.8"):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect((probe, 9))  # no packet is sent; this only picks a route
            found.append(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.append(info[4][0])
    except OSError:
        pass
    seen, result = set(), []
    for ip in found:
        if ip not in seen and _is_private(ip):
            seen.add(ip)
            result.append(ip)
    return result


# ---- X25519 (RFC 7748), for the link key agreement ---------------------------------------
# Plain integers: the standard library has no X25519. Not constant-time, which is fine for a
# key pair that lives for one link on your own network.

_P = 2 ** 255 - 19


def _x25519(k, u):
    k = bytearray(k)
    k[0] &= 248
    k[31] = (k[31] & 127) | 64
    n = int.from_bytes(bytes(k), "little")
    u = bytearray(u)
    u[31] &= 127
    x1 = int.from_bytes(bytes(u), "little") % _P
    x2, z2, x3, z3, swap = 1, 0, x1, 1, 0
    for t in range(254, -1, -1):
        bit = (n >> t) & 1
        if swap ^ bit:
            x2, x3, z2, z3 = x3, x2, z3, z2
        swap = bit
        a, b = (x2 + z2) % _P, (x2 - z2) % _P
        aa, bb = a * a % _P, b * b % _P
        e = (aa - bb) % _P
        c, d = (x3 + z3) % _P, (x3 - z3) % _P
        da, cb = d * a % _P, c * b % _P
        x3 = (da + cb) ** 2 % _P
        z3 = x1 * (da - cb) ** 2 % _P
        x2 = aa * bb % _P
        z2 = e * (aa + 121665 * e) % _P
    if swap:
        x2, x3, z2, z3 = x3, x2, z3, z2
    return (x2 * pow(z2, _P - 2, _P) % _P).to_bytes(32, "little")


def _x25519_public(private):
    return _x25519(private, (9).to_bytes(32, "little"))


def _link_key(secret, computer_pub, phone_pub, computer_priv):
    """The long-term key: never in the QR code, never on the network. Mirrors Linker.accept."""
    shared = _x25519(computer_priv, phone_pub)
    if shared == bytes(32):
        raise ValueError("bad public key")
    return hmac.new(secret, b"shell-link-v1" + computer_pub + phone_pub + shared, hashlib.sha256).digest()


def _b64(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _unb64(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _hmac(key_hex, message):
    return hmac.new(bytes.fromhex(key_hex), message.encode(), hashlib.sha256).hexdigest()


def _signed_headers(phone, verb, name="", sha="", file=""):
    t = str(int(time.time()))
    nonce = secrets.token_hex(8)
    headers = {
        "X-Shell-Device": phone["computerId"],
        "X-Shell-Time": t,
        "X-Shell-Nonce": nonce,
        "X-Shell-Sig": _hmac(phone["key"], "%s\n%s\n%s\n%s\n%s\n%s" % (verb, name, t, sha, nonce, file)),
    }
    if file:
        headers["X-Shell-File"] = file
    return headers


def _http(method, url, headers, body=None, timeout=15):
    """(status, body bytes, response headers)."""
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read(), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read(), e.headers


def _genuine(phone, nonce, status, body, headers):
    """True if the answer was signed by the phone with this link's key."""
    want = _hmac(phone["key"], "reply\n%s\n%d\n%s" % (nonce, status, hashlib.sha256(body).hexdigest()))
    return hmac.compare_digest(want, (headers.get("X-Shell-Reply-Sig") or "").lower())


def hello(phone, host, port, timeout=3):
    """The phone's details if the device at host:port is really it, else None."""
    h = _signed_headers(phone, "hello")
    try:
        status, body, headers = _http("GET", "http://%s:%d/hello" % (host, port), h, timeout=timeout)
    except (OSError, ValueError):
        return None
    if status != 200 or not _genuine(phone, h["X-Shell-Nonce"], status, body, headers):
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def call(cfg, pid, method, path, verb, name="", body=None, sha="", file="", extra=None, timeout=30):
    """
    A signed request to a phone. Finds the phone again on the LAN if its address changed, and
    refuses to believe an answer the phone didn't sign.
    """
    phone = cfg["phones"][pid]
    if body is not None and not hello(phone, phone["host"], phone["port"]):
        # Before sending an app anywhere, make sure the device at the saved address is really
        # the phone; addresses get reused, and something else could be answering there.
        if not rediscover(cfg, pid):
            raise Fail(
                "Can't reach %s. Open %s on the phone and keep it on screen, and check the phone "
                "and this computer are on the same Wi-Fi (not a guest network, no VPN)."
                % (phone["name"], NAME))
        phone = cfg["phones"][pid]
    headers = _signed_headers(phone, verb, name, sha, file)
    headers.update(extra or {})
    url = "http://%s:%d%s" % (phone["host"], phone["port"], path)
    try:
        status, raw, rh = _http(method, url, headers, body, timeout)
    except (OSError, ValueError):
        if not rediscover(cfg, pid):
            raise Fail(
                "Can't reach %s. Open %s on the phone and keep it on screen, and check the phone "
                "and this computer are on the same Wi-Fi (not a guest network, no VPN)."
                % (phone["name"], NAME))
        phone = cfg["phones"][pid]
        headers = _signed_headers(phone, verb, name, sha, file)
        headers.update(extra or {})
        url = "http://%s:%d%s" % (phone["host"], phone["port"], path)
        try:
            status, raw, rh = _http(method, url, headers, body, timeout)
        except (OSError, ValueError) as e:
            raise Fail("Found %s but the request failed: %s" % (phone["name"], e))
    text = raw.decode("utf-8", "replace").strip()
    if status == 409:
        raise Fail("%s no longer trusts this computer (it was unlinked, or %s was reinstalled). "
                   "Link it again: homebaked.py pair" % (phone["name"], NAME))
    if status == 401 and "clock skew" in text:
        raise Fail("The phone and this computer disagree about the time by more than 5 minutes. "
                   "Turn on automatic time on both.")
    if not _genuine(phone, headers["X-Shell-Nonce"], status, raw, rh):
        raise Fail("Something on the network answered instead of %s, so nothing it said can be "
                   "trusted. Check you're on your own Wi-Fi, then try again." % phone["name"])
    return status, text


# ---- mDNS discovery -------------------------------------------------------------------

def _enc_name(name):
    return b"".join(bytes([len(p)]) + p.encode() for p in name.split(".") if p) + b"\0"


def _dec_name(pkt, off):
    labels, jumped, end = [], False, off
    for _ in range(128):
        n = pkt[off]
        if n == 0:
            off += 1
            break
        if n & 0xC0 == 0xC0:
            if not jumped:
                end = off + 2
            off = ((n & 0x3F) << 8) | pkt[off + 1]
            jumped = True
            continue
        labels.append(pkt[off + 1:off + 1 + n].decode("utf-8", "replace"))
        off += 1 + n
    return ".".join(labels), (end if jumped else off)


def _parse(pkt):
    """Every resource record in a DNS packet, as (name, type, data)."""
    qd, an, ns, ar = struct.unpack(">4H", pkt[4:12])
    off = 12
    for _ in range(qd):
        _, off = _dec_name(pkt, off)
        off += 4
    recs = []
    for _ in range(an + ns + ar):
        name, off = _dec_name(pkt, off)
        rtype, _, _, rdlen = struct.unpack(">HHIH", pkt[off:off + 10])
        off += 10
        rd = pkt[off:off + rdlen]
        if rtype == 12:
            recs.append((name.lower(), 12, _dec_name(pkt, off)[0]))
        elif rtype == 33:
            port = struct.unpack(">H", rd[4:6])[0]
            recs.append((name.lower(), 33, (_dec_name(pkt, off + 6)[0].lower(), port)))
        elif rtype == 16:
            kv, i = {}, 0
            while i < len(rd):
                s = rd[i + 1:i + 1 + rd[i]].decode("utf-8", "replace")
                i += 1 + rd[i]
                if "=" in s:
                    k, v = s.split("=", 1)
                    kv[k] = v
            recs.append((name.lower(), 16, kv))
        elif rtype == 1 and rdlen == 4:
            recs.append((name.lower(), 1, socket.inet_ntoa(rd)))
        off += rdlen
    return recs


def discover(timeout=2.5):
    """Phones advertising the service on the LAN: [{"id", "host", "port"}]."""
    query = struct.pack(">6H", 0, 0, 1, 0, 0, 0) + _enc_name(SERVICE) + struct.pack(">HH", 12, 0x8001)
    socks = []
    # One socket per interface, so the query goes out on Wi-Fi even if another adapter
    # (VPN, virtual machine) holds the default route. Answers come back unicast.
    for ip in local_ipv4s() or [""]:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 255)
            if ip:
                s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(ip))
            s.bind((ip, 0))
            s.sendto(query, ("224.0.0.251", 5353))
            socks.append(s)
        except OSError:
            pass
    # Some responders only answer to the multicast group; listen there too when possible.
    try:
        m = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        m.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        if hasattr(socket, "SO_REUSEPORT"):
            try:
                m.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            except OSError:
                pass
        m.bind(("", 5353))
        m.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP,
                     socket.inet_aton("224.0.0.251") + socket.inet_aton("0.0.0.0"))
        socks.append(m)
    except OSError:
        pass

    recs, sources = [], {}  # sources: instance -> addresses its answers came from
    deadline = time.time() + timeout
    for s in socks:
        s.setblocking(False)
    while time.time() < deadline:
        import select
        ready, _, _ = select.select(socks, [], [], max(0.05, deadline - time.time()))
        for s in ready:
            try:
                pkt, addr = s.recvfrom(9000)
                got = _parse(pkt)
            except (OSError, ValueError, IndexError, struct.error):
                continue
            recs.extend(got)
            for name, rtype, data in got:
                if rtype == 33:
                    sources.setdefault(name, []).append(addr[0])
    for s in socks:
        s.close()

    svc = SERVICE.lower()
    instances = {data.lower() for name, rtype, data in recs if rtype == 12 and name == svc}
    found, seen = [], set()
    for inst in instances:
        srv = next((d for n, t, d in recs if t == 33 and n == inst), None)
        if not srv:
            continue
        txt = next((d for n, t, d in recs if t == 16 and n == inst), {})
        # A host can answer with several addresses (Wi-Fi, VPN, containers). The address the
        # answer arrived from is the likeliest to work, so it goes first.
        hosts = sources.get(inst, []) + [d for n, t, d in recs if t == 1 and n == srv[0]]
        for host in hosts:
            if (host, srv[1]) not in seen:
                seen.add((host, srv[1]))
                found.append({"id": txt.get("id", ""), "host": host, "port": srv[1]})
    return found


def rediscover(cfg, pid):
    """Look for a linked phone on the LAN and update its address. True if found."""
    phone = cfg["phones"][pid]
    candidates = discover()
    candidates.sort(key=lambda c: c["id"] != pid)  # its own id first
    for c in candidates:
        info = hello(phone, c["host"], c["port"])
        if info:
            phone["host"], phone["port"] = c["host"], c["port"]
            phone["lastSeen"] = int(time.time())
            save_phones(cfg)
            return True
    return False


# ---- Linking --------------------------------------------------------------------------

def _derive(pending, info):
    """The link key from the phone's public half (callback or /link), or None if unusable."""
    try:
        phone_pub = _unb64(info["pub"])
        if len(phone_pub) != 32:
            return None
        return _link_key(bytes.fromhex(pending["secret"]), bytes.fromhex(pending["pub"]), phone_pub,
                         bytes.fromhex(pending["priv"])).hex()
    except (KeyError, ValueError, TypeError):
        return None


def _record_link(pending, host, info, key, port=None):
    cfg = load_phones()
    pid = info.get("phoneId") or secrets.token_hex(4)
    cfg["phones"][pid] = {
        "name": info.get("phone") or "phone",
        "computerId": pending["id"],
        "key": key,
        "host": host,
        "port": int(port or info.get("port") or 8080),
        "linked": int(time.time()),
        "lastSeen": int(time.time()),
    }
    cfg["default"] = pid
    save_phones(cfg)
    return cfg["phones"][pid]["name"]


def computer_id():
    """
    This computer's id, the same for every link. Linking a phone again then replaces the old
    entry on the phone instead of adding a second one with the same name.
    """
    p = _path("computer.json")
    data = _read(p, {})
    if not re.fullmatch(r"[0-9a-f]{8,32}", str(data.get("id", ""))):
        data = {"id": secrets.token_hex(8)}
        _write_private(p, data)
    return data["id"]


def cmd_pair(args):
    if "--wait" in args:
        return pair_wait()
    computer = re.sub(r"[^A-Za-z0-9._ -]", "", socket.gethostname()).strip()[:40] or "computer"
    # The QR carries a one-time secret and this computer's X25519 public key. The private key
    # stays in pending.json (readable only by you) until linking finishes, then is deleted.
    priv = secrets.token_bytes(32)
    pending = {
        "id": computer_id(),
        "secret": secrets.token_hex(32),
        "priv": priv.hex(),
        "pub": _x25519_public(priv).hex(),
        "name": computer,
        "exp": int(time.time()) + QR_LIFETIME,
        "linked": None,
    }
    # A one-shot listener for the phone's "linked" callback, on any free port.
    probe = socket.socket()
    probe.bind(("0.0.0.0", 0))
    pending["port"] = probe.getsockname()[1]
    probe.close()
    _write_private(_path("pending.json"), pending)

    kwargs = {"stdin": subprocess.DEVNULL, "stdout": subprocess.DEVNULL, "stderr": subprocess.DEVNULL}
    if os.name == "nt":
        kwargs["creationflags"] = 0x00000008 | 0x00000200  # DETACHED_PROCESS | NEW_PROCESS_GROUP
    else:
        kwargs["start_new_session"] = True
    subprocess.Popen([sys.executable, os.path.abspath(__file__), "_listen"], **kwargs)

    ips = local_ipv4s()
    if not ips:
        out("WARNING: this computer doesn't seem to be on a home network (no private IPv4 address).")
    cb = ",".join("%s:%d" % (ip, pending["port"]) for ip in ips[:4])
    link = "%s://link?v=%d&id=%s&k=%s&c=%s&n=%s&cb=%s&exp=%d" % (
        SCHEME, PROTOCOL, pending["id"], _b64(bytes.fromhex(pending["secret"])),
        _b64(bytes.fromhex(pending["pub"])), urllib.request.quote(computer), cb, pending["exp"])

    modules = qr.encode(link)
    png, svg = _path("link-qr.png"), _path("link-qr.svg")
    for f in (png, svg):  # the QR carries the key: private, and deleted once linked
        if os.path.exists(f):
            os.remove(f)
        os.close(os.open(f, os.O_WRONLY | os.O_CREAT, 0o600))
    qr.write_png(modules, png)
    qr.write_svg(modules, svg)
    if "--open" in args:
        _open_file(png)
    out("QR_PNG=%s" % png)
    out("QR_SVG=%s" % svg)
    if "--text" in args:
        out(qr.to_text(modules))
    out("Show the QR code to the person. On the phone: open %s, tap Scan QR code, then Link." % NAME)
    out("It expires in %d minutes. Then run: homebaked.py pair --wait" % (QR_LIFETIME // 60))


def _open_file(path):
    try:
        if os.name == "nt":
            os.startfile(path)  # noqa
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        pass


def cmd_listen(_args):
    """Detached helper started by `pair`: waits for the phone's signed callback."""
    pending = _read(_path("pending.json"), None)
    if not pending:
        return

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_POST(self):
            if self.path != "/linked":
                self.send_response(404)
                self.end_headers()
                return
            body = self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 4096))
            ok, key = False, None
            try:
                info = json.loads(body)
                key = _derive(pending, info)
                msg = "linked\n%s\n%s\n%s" % (info["phoneId"], self.headers.get("X-Shell-Time", ""),
                                              hashlib.sha256(body).hexdigest())
                ok = (key is not None
                      and self.headers.get("X-Shell-Device") == pending["id"]
                      and hmac.compare_digest(_hmac(key, msg), self.headers.get("X-Shell-Sig", ""))
                      and abs(time.time() - int(self.headers.get("X-Shell-Time", "0"))) < 600)
            except (ValueError, KeyError, TypeError):
                ok = False
            self.send_response(200 if ok else 401)
            self.end_headers()
            if ok:
                name = _record_link(pending, self.client_address[0], info, key)
                pending["linked"] = name
                _write_private(_path("pending.json"), pending)
                server.done = True

    try:
        server = http.server.HTTPServer(("0.0.0.0", pending["port"]), Handler)
    except OSError:
        return
    server.done = False
    server.timeout = 1
    while not server.done and time.time() < pending["exp"] + 60:
        server.handle_request()
    server.server_close()


def _finish_pairing():
    for f in ("pending.json", "link-qr.png", "link-qr.svg"):
        try:
            os.remove(_path(f))
        except OSError:
            pass


def _fetch_link(pending, host, port):
    """Ask a discovered phone for its half of the key exchange; check it signed the answer."""
    try:
        status, body, headers = _http("GET", "http://%s:%d/link?id=%s" % (host, port, pending["id"]),
                                      {}, timeout=3)
        if status != 200:
            return None, None
        info = json.loads(body)
    except (OSError, ValueError):
        return None, None
    key = _derive(pending, info)
    want = key and _hmac(key, "link\n%s\n%s" % (pending["id"], hashlib.sha256(body).hexdigest()))
    if not want or not hmac.compare_digest(want, (headers.get("X-Shell-Reply-Sig") or "").lower()):
        return None, None
    return info, key


def pair_wait(limit=270):
    pending = _read(_path("pending.json"), None)
    if not pending:
        raise Fail("No link in progress. Run: homebaked.py pair")
    start = time.time()
    next_scan = start + 10
    # Claude may build an app between showing the code and calling this, so the code can be
    # past its expiry by now even though the phone linked in time. Look once straight away.
    if time.time() > pending["exp"]:
        next_scan = start
    while time.time() - start < limit:
        p = _read(_path("pending.json"), pending)
        if p.get("linked"):
            out("LINKED: %s. Claude can now put apps on this phone." % p["linked"])
            _finish_pairing()
            return
        # The callback can be stopped by this computer's firewall. The phone still stored the
        # key, so look for it on the LAN and ask whether it knows us.
        if time.time() >= next_scan:
            next_scan = time.time() + 15
            for c in discover():
                info, key = _fetch_link(pending, c["host"], c["port"])
                if info:
                    name = _record_link(pending, c["host"], info, key, c["port"])
                    out("LINKED: %s. Claude can now put apps on this phone." % name)
                    _finish_pairing()
                    return
        if time.time() > pending["exp"] + 60:
            break
        time.sleep(1)
    if time.time() > pending["exp"]:
        _finish_pairing()
        raise Fail("The QR code expired before the phone linked. Run homebaked.py pair for a new one.")
    raise Fail("Still waiting for the phone. If it was scanned and Link was tapped, run "
               "homebaked.py pair --wait again; otherwise make sure %s is open on the phone." % NAME)


# ---- Apps -----------------------------------------------------------------------------

_URL = re.compile(r"""(?<![\w-])(?:https?:)?//([a-z0-9-]+(?:\.[a-z0-9-]+)+)""", re.I)
_TEXT = (".html", ".htm", ".js", ".mjs", ".css", ".json", ".svg")
_SKIP = {".git", "__MACOSX", ".DS_Store", "node_modules"}


def _check_app(folder):
    """Problems that would make the app fail on the phone. Returns (errors, warnings, manifest)."""
    errors, warnings, manifest = [], [], {}
    if not os.path.isfile(os.path.join(folder, "index.html")):
        errors.append("there's no index.html in %s" % folder)
    mpath = os.path.join(folder, "manifest.json")
    if os.path.exists(mpath):
        try:
            with open(mpath, encoding="utf-8") as f:
                manifest = json.load(f)
        except ValueError as e:
            errors.append("manifest.json isn't valid JSON: %s" % e)
    allowed = [str(h).lower() for h in manifest.get("network", [])]

    def is_allowed(host):
        return any(host.endswith(r[1:]) if r.startswith("*.") else host == r for r in allowed)

    outside = {}
    for root, dirs, files in os.walk(folder):
        dirs[:] = [d for d in dirs if d not in _SKIP and not d.startswith(".")]
        for fn in files:
            if not fn.lower().endswith(_TEXT):
                continue
            try:
                with open(os.path.join(root, fn), encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            for m in _URL.finditer(text):
                host = m.group(1).lower()
                if host in ("www.w3.org", "w3.org") or is_allowed(host):
                    continue
                outside.setdefault(host, fn)
    for host, fn in sorted(outside.items()):
        warnings.append("%s mentions %s, which isn't in manifest.json \"network\". On the phone, "
                        "anything loaded from there is blocked." % (fn, host))
    return errors, warnings, manifest


def _zip(folder):
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(folder):
            dirs[:] = sorted(d for d in dirs if d not in _SKIP and not d.startswith("."))
            for fn in sorted(files):
                if fn.startswith(".") or fn in _SKIP:
                    continue
                full = os.path.join(root, fn)
                z.write(full, os.path.relpath(full, folder).replace(os.sep, "/"))
    return buf.getvalue()


def _upload(cfg, pid, name, filename, data, content_type):
    sha = hashlib.sha256(data).hexdigest()
    boundary = uuid.uuid4().hex
    body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
             "Content-Type: %s\r\n\r\n") % (boundary, filename, content_type)).encode() \
        + data + ("\r\n--%s--\r\n" % boundary).encode()
    status, text = call(cfg, pid, "POST", "/upload?name=" + urllib.request.quote(name), "upload",
                        name=name, body=body, sha=sha, file=filename,
                        extra={"X-Shell-Sha256": sha,
                               "Content-Type": "multipart/form-data; boundary=" + boundary},
                        timeout=120)
    if status != 200:
        raise Fail("The phone refused %s: %s" % (filename, text.strip()))
    return text.strip()


def cmd_push(args, phone_arg):
    if not args:
        raise Fail("Usage: homebaked.py push <app folder> [file ...]")
    folder = os.path.abspath(args[0])
    if not os.path.isdir(folder):
        raise Fail("%s isn't a folder" % args[0])
    name = sanitize(os.path.basename(folder))
    if not name:
        raise Fail("The folder name needs some letters or digits.")
    errors, warnings, manifest = _check_app(folder)
    for w in warnings:
        out("WARNING: " + w)
    if errors:
        raise Fail("; ".join(errors))
    cfg = load_phones()
    pid, phone = pick_phone(cfg, phone_arg)
    label = manifest.get("name") or name

    files = args[1:]
    if files:
        for f in files:
            rel = os.path.relpath(os.path.abspath(os.path.join(folder, f)
                                                  if not os.path.isabs(f) else f), folder)
            if rel.startswith("..") or os.sep in rel or "/" in rel:
                raise Fail("%s: one-file pushes only cover files at the top of the app folder; "
                           "push the whole folder instead." % f)
            with open(os.path.join(folder, rel), "rb") as fh:
                data = fh.read()
            _upload(cfg, pid, name, rel, data, "application/octet-stream")
        out("SENT: %s to %s (%s). If it's open on the phone, it reloads." %
            (", ".join(files), phone["name"], label))
    else:
        data = _zip(folder)
        if len(data) > 20 * 1024 * 1024:
            out("WARNING: the app is %.1f MB; pushes this big are slow." % (len(data) / 1048576))
        _upload(cfg, pid, name, name + ".zip", data, "application/zip")
        out("SENT: %s (\"%s\", %d KB) to %s. It's on the phone now; if it was open, it reloaded."
            % (name, label, max(1, len(data) // 1024), phone["name"]))
    cfg["default"] = pid
    save_phones(cfg)


def cmd_list(phone_arg):
    cfg = load_phones()
    pid, phone = pick_phone(cfg, phone_arg)
    status, text = call(cfg, pid, "GET", "/apps", "apps")
    if status != 200:
        raise Fail("The phone said: %s" % text)
    apps = json.loads(text)
    if not apps:
        out("No apps on %s yet." % phone["name"])
        return
    out("Apps on %s:" % phone["name"])
    for a in apps:
        extra = []
        if a.get("network"):
            extra.append("online: " + ", ".join(a["network"]))
        if a.get("shared"):
            extra.append("shares: " + ", ".join(a["shared"]))
        out("  %-24s %s%s" % (a["id"], a.get("name", ""), ("  [" + "; ".join(extra) + "]") if extra else ""))


def cmd_delete(args, phone_arg):
    if not args:
        raise Fail("Usage: homebaked.py delete <app>")
    name = sanitize(args[0])
    cfg = load_phones()
    pid, phone = pick_phone(cfg, phone_arg)
    status, text = call(cfg, pid, "POST", "/delete?name=" + name, "delete", name=name)
    if status != 200:
        raise Fail("The phone said: %s" % text.strip())
    out("DELETED: %s from %s, with its saved data." % (name, phone["name"]))


def cmd_status(phone_arg):
    cfg = load_phones()
    if not cfg["phones"]:
        out("No phone linked yet. Run: homebaked.py pair")
        return
    for pid, phone in cfg["phones"].items():
        if phone_arg and phone_arg.lower() not in (pid + phone["name"].lower()):
            continue
        info = hello(phone, phone["host"], phone["port"]) or (
            rediscover(cfg, pid) and hello(cfg["phones"][pid], cfg["phones"][pid]["host"],
                                           cfg["phones"][pid]["port"]))
        mark = " (default)" if cfg.get("default") == pid else ""
        if info:
            out("%s%s: reachable at %s:%d, %s %s" % (phone["name"], mark, cfg["phones"][pid]["host"],
                                                   cfg["phones"][pid]["port"], info.get("app"),
                                                   info.get("version")))
        else:
            out("%s%s: not reachable right now (open %s on it, same Wi-Fi)" % (phone["name"], mark, NAME))


def cmd_unlink(args):
    cfg = load_phones()
    pid, phone = pick_phone(cfg, args[0] if args else None)
    del cfg["phones"][pid]
    if cfg.get("default") == pid:
        cfg["default"] = next(iter(cfg["phones"]), None)
    save_phones(cfg)
    out("Forgot %s on this computer. To stop it trusting this computer too, tap Unlink in %s on the phone."
        % (phone["name"], NAME))


def main(argv):
    args = list(argv)
    phone_arg = None
    if "--phone" in args:
        i = args.index("--phone")
        if i + 1 >= len(args):
            raise Fail("--phone needs a name")
        phone_arg = args[i + 1]
        del args[i:i + 2]
    if not args or args[0] in ("-h", "--help", "help"):
        out(__doc__.strip())
        return
    cmd, rest = args[0], args[1:]
    if cmd == "pair":
        cmd_pair(rest)
    elif cmd == "_listen":
        cmd_listen(rest)
    elif cmd == "push":
        cmd_push(rest, phone_arg)
    elif cmd == "list":
        cmd_list(phone_arg)
    elif cmd == "delete":
        cmd_delete(rest, phone_arg)
    elif cmd in ("status", "phones"):
        cmd_status(phone_arg)
    elif cmd == "unlink":
        cmd_unlink(rest)
    else:
        raise Fail("Unknown command %r. Run homebaked.py --help" % cmd)


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except Fail as e:
        out("ERROR: %s" % e)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)
