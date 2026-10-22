package com.nbitslabs.thumbs.view

/**
 * The browser page for a live view link: the screen stream plus a feed of agents' tool calls.
 * Self-contained (no external resources), with the script allowed only by [nonce].
 */
object ViewPage {
    fun html(token: String, nonce: String, expiresAt: Long): String = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>Thumbs live view</title>
<style>
  :root { color-scheme: dark; --bg: #121014; --panel: #1d1b20; --text: #e6e0e9; --muted: #a59fae; --ok: #7bd88f; --err: #ff8a80; --accent: #d0bcff; }
  * { box-sizing: border-box; }
  body { margin: 0; height: 100vh; display: flex; background: var(--bg); color: var(--text); font: 14px/1.4 system-ui, sans-serif; }
  main { flex: 1; display: flex; align-items: center; justify-content: center; padding: 16px; min-width: 0; position: relative; }
  #screen { max-height: calc(100vh - 32px); max-width: 100%; border-radius: 18px; background: #000; box-shadow: 0 0 0 1px #333; }
  #overlay { position: absolute; inset: 0; display: none; align-items: center; justify-content: center; color: var(--muted); font-size: 16px; text-align: center; padding: 24px; }
  aside { width: 360px; background: var(--panel); display: flex; flex-direction: column; border-left: 1px solid #2c2930; }
  header { padding: 14px 16px; border-bottom: 1px solid #2c2930; }
  h1 { font-size: 16px; margin: 0 0 4px; display: flex; align-items: center; gap: 8px; }
  .dot { width: 9px; height: 9px; border-radius: 50%; background: var(--muted); }
  .live .dot { background: #ff5252; box-shadow: 0 0 6px #ff5252; }
  #status, #expiry { color: var(--muted); font-size: 12px; }
  #feed { list-style: none; margin: 0; padding: 0; overflow-y: auto; flex: 1; }
  #feed li { padding: 8px 16px; border-bottom: 1px solid #262329; }
  .tool { font-family: ui-monospace, monospace; font-weight: 600; }
  .tool.err { color: var(--err); }
  .meta { color: var(--muted); font-size: 12px; word-break: break-word; }
  .empty { color: var(--muted); padding: 16px; }
  @media (max-width: 800px) { body { flex-direction: column; height: auto; } aside { width: auto; border-left: 0; } #screen { max-height: 80vh; } }
</style>
</head>
<body>
<main>
  <img id="screen" alt="Phone screen">
  <div id="overlay"></div>
</main>
<aside>
  <header id="head">
    <h1><span class="dot"></span>Thumbs live view</h1>
    <div id="status">Connecting…</div>
    <div id="expiry"></div>
  </header>
  <ul id="feed"><li class="empty">Agent actions will appear here.</li></ul>
</aside>
<script nonce="$nonce">
(() => {
  const token = "$token";
  const q = "t=" + encodeURIComponent(token);
  const img = document.getElementById("screen");
  const overlay = document.getElementById("overlay");
  const head = document.getElementById("head");
  const status = document.getElementById("status");
  const feed = document.getElementById("feed");
  const expiresAt = $expiresAt;
  let ended = false;

  document.getElementById("expiry").textContent = "Link expires " + new Date(expiresAt).toLocaleString();

  function end(message) {
    ended = true;
    head.classList.remove("live");
    status.textContent = message;
    overlay.textContent = message;
    overlay.style.display = "flex";
    img.removeAttribute("src");
  }

  function connectStream() {
    if (ended) return;
    img.src = "/view/stream?" + q + "&n=" + Date.now();
  }
  img.addEventListener("load", () => { if (!ended) head.classList.add("live"); });
  img.addEventListener("error", () => {
    head.classList.remove("live");
    if (Date.now() >= expiresAt) return end("This live view link has expired.");
    setTimeout(connectStream, 2000);
  });
  connectStream();

  const events = new EventSource("/view/events?" + q);
  events.addEventListener("status", (e) => {
    const s = JSON.parse(e.data);
    status.textContent = [s.foreground ? "Foreground: " + s.foreground : null, s.screen, s.viewers + " watching"].filter(Boolean).join(" · ");
  });
  events.addEventListener("audit", (e) => {
    const a = JSON.parse(e.data);
    const empty = feed.querySelector(".empty");
    if (empty) empty.remove();
    const li = document.createElement("li");
    const tool = document.createElement("div");
    tool.className = "tool" + (a.ok ? "" : " err");
    tool.textContent = a.tool;
    const meta = document.createElement("div");
    meta.className = "meta";
    meta.textContent = new Date(a.time).toLocaleTimeString() + " · " + a.key + " · " + a.ms + "ms" + (a.args ? " · " + a.args : "") + (a.detail ? " · " + a.detail : "");
    li.append(tool, meta);
    feed.prepend(li);
    while (feed.children.length > 100) feed.lastChild.remove();
  });
  events.addEventListener("ended", () => { events.close(); end("This live view link was ended on the phone or has expired."); });
  events.onerror = () => { if (Date.now() >= expiresAt) { events.close(); end("This live view link has expired."); } };
})();
</script>
</body>
</html>
""".trimIndent()
}
