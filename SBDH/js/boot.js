/* =========================================================================
 *  boot.js  ::  matrix rain + boot sequence
 * ====================================================================== */

/* ---------- matrix rain ---------- */
(function matrix(){
  const c = document.getElementById('matrix');
  const ctx = c.getContext('2d');
  let w, h, cols, drops;
  const glyphs = "01░▒▓<>/\\{}[]=+*アカサタナハマヤラABCDEF0123456789".split("");

  function resize(){
    w = c.width = innerWidth;
    h = c.height = innerHeight;
    cols = Math.floor(w / 16);
    drops = Array(cols).fill(0).map(() => Math.random() * -h);
  }
  resize();
  addEventListener('resize', resize);

  function draw(){
    ctx.fillStyle = "rgba(4,7,10,0.08)";
    ctx.fillRect(0, 0, w, h);
    ctx.font = "15px monospace";
    for (let i = 0; i < cols; i++){
      const ch = glyphs[(Math.random() * glyphs.length) | 0];
      const x = i * 16, y = drops[i];
      ctx.fillStyle = Math.random() > 0.975 ? "#eafff6" : "#00ff9c";
      ctx.fillText(ch, x, y);
      drops[i] = y > h && Math.random() > 0.975 ? 0 : y + 16;
    }
    requestAnimationFrame(draw);
  }
  draw();
})();

/* ---------- boot sequence ---------- */
(function boot(){
  const log = document.getElementById('bootlog');
  const bootEl = document.getElementById('boot');
  const content = document.getElementById('content');

  const lines = [
    "[ 0.000001 ] initializing secure channel SBDH-01 ...",
    "[ 0.001423 ] handshake .................. OK",
    "[ 0.014502 ] verifying recipient: SHASHANK ... MATCH",
    "[ 0.041190 ] mounting /dev/birthday ...... OK",
    "[ 0.092311 ] decrypting greeting payload . OK",
    "[ 0.150872 ] WARNING: 3 signal fragments scattered across source",
    "[ 0.151002 ] WARNING: decoys present. trust nothing.",
    "[ 0.200544 ] channel ready. welcome, operator.",
    "",
  ];

  let i = 0;
  function typeLine(){
    if (i < lines.length){
      log.textContent += lines[i] + "\n";
      i++;
      setTimeout(typeLine, 90 + Math.random() * 160);
    } else {
      log.classList.add('cursorline');
      setTimeout(reveal, 650);
    }
  }
  function reveal(){
    bootEl.classList.add('hidden');
    content.classList.remove('hidden');
    if (window.__termWelcome) window.__termWelcome();
    // focus the terminal WITHOUT letting the browser scroll it into view,
    // then snap to the top so he reads the birthday message first.
    document.getElementById('termInput')?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }

  // allow skip
  function skip(){ i = lines.length; }
  addEventListener('keydown', function once(e){
    if (e.key === 'Enter' || e.key === ' '){ skip(); removeEventListener('keydown', once); }
  });

  typeLine();
})();
