/* =========================================================================
 *  terminal.js  ::  interactive shell for stage 01
 * ====================================================================== */
(function(){
  const body  = document.getElementById('termBody');
  const input = document.getElementById('termInput');
  if (!body || !input) return;

  let hintLevel = 0;
  const history = [];
  let hIdx = -1;

  function print(text, cls){
    const div = document.createElement('div');
    div.className = 'line' + (cls ? ' ' + cls : '');
    div.textContent = text;
    body.appendChild(div);
    body.scrollTop = body.scrollHeight;
  }
  function printHTML(html){
    const div = document.createElement('div');
    div.className = 'line';
    div.innerHTML = html;
    body.appendChild(div);
    body.scrollTop = body.scrollHeight;
  }

  const FILES = ['index.html', 'css/main.css', 'js/boot.js', 'js/config.js', 'js/terminal.js', '.secret'];

  const COMMANDS = {
    help(){
      print("available commands:", 'ok');
      print("  help            this list");
      print("  whoami          who's there");
      print("  ls              list files in this node");
      print("  cat <file>      read a file (you have eyes — use them)");
      print("  scan            scan for hidden signals");
      print("  hint            ask for a nudge (escalates)");
      print("  decrypt <key>   open the sealed next-node address");
      print("  clear           wipe the screen");
    },
    whoami(){
      print("shashank — birthday boy, breaker of systems, certified menace. 🎂", 'ok');
    },
    ls(){
      print(FILES.join("    "), 'dim');
      print("(tip: the browser's 'view source' & devtools see more than 'cat' does)", 'dim');
    },
    cat(arg){
      if (!arg){ print("usage: cat <file>", 'err'); return; }
      if (arg === '.secret'){
        print("cat: .secret: permission denied — you are not root (yet).", 'err'); return;
      }
      if (FILES.includes(arg)){
        print(`cat: ${arg}: streaming this file through the terminal would be too easy.`, 'dim');
        print("open it for real. fragments hide in comments and dead constants.", 'dim');
        return;
      }
      print(`cat: ${arg}: no such file`, 'err');
    },
    scan(){
      print("scanning node SBDH-01 for signal fragments ...", 'dim');
      setTimeout(()=>print("  > fragment density: 3 genuine, decoys interleaved", 'ok'), 350);
      setTimeout(()=>print("  > locations: 1 markup comment, 1 style rule, 1 script constant", 'ok'), 700);
      setTimeout(()=>print("  > encodings differ per fragment. classic.", 'dim'), 1050);
    },
    hint(){
      const hints = [
        "three fragments. labelled like 'sig.0', 'sig-1', 'SIG_2'. find all three.",
        "encodings: one is base64, one is hex, one is just... backwards. decode each.",
        "concatenate the decoded fragments in order (0,1,2) — no spaces — to forge the key.",
        "then: decrypt <KEY>.  decoys decode to taunts, not keys. that's the point.",
      ];
      if (hintLevel < hints.length){ print("hint> " + hints[hintLevel], 'ok'); hintLevel++; }
      else print("hint> no more hints. you're a cyber engineer. you've got this. 🫡", 'dim');
    },
    sudo(){ print("nice try. this isn't that kind of party. 😄", 'err'); },
    clear(){ body.innerHTML = ''; },

    decrypt(arg){
      if (!arg){ print("usage: decrypt <key>", 'err'); return; }
      const res = tryDecrypt(arg.trim());
      if (res.ok){
        print("key accepted. seal broken.", 'ok');
        print("decrypting next-node address ...", 'dim');
        setTimeout(()=>unlockGate(res.path), 600);
      } else {
        print("ACCESS DENIED — key produced noise:", 'err');
        print("  " + JSON.stringify(res.noise || ''), 'dim');
        print("(decoys are designed to fail. assemble the real fragments.)", 'dim');
      }
    },
  };

  function unlockGate(path){
    const gate = document.getElementById('gate');
    const reveal = document.getElementById('gateReveal');
    gate.classList.remove('locked');
    gate.classList.add('unlocked');
    gate.querySelector('.lock-icon').textContent = '🔓';
    gate.querySelector('.gate-text').textContent = 'NODE UNLOCKED';
    reveal.classList.remove('hidden');
    reveal.innerHTML =
      `<div class="ok" style="color:var(--neon)">next node: <strong>${path}</strong></div>` +
      `<a href="${path}">ENTER NODE 02 &nbsp;➜</a>`;
    print("→ proceed to " + path, 'ok');
    reveal.scrollIntoView({behavior:'smooth', block:'center'});
  }

  function run(raw){
    const cmd = raw.trim();
    print("> " + cmd, 'echo');
    if (!cmd) return;
    history.unshift(cmd); hIdx = -1;
    const [name, ...rest] = cmd.split(/\s+/);
    const fn = COMMANDS[name.toLowerCase()];
    if (fn) fn(rest.join(' '));
    else print(name + ": command not found  (try 'help')", 'err');
  }

  input.addEventListener('keydown', (e)=>{
    if (e.key === 'Enter'){ run(input.value); input.value = ''; }
    else if (e.key === 'ArrowUp'){ if (hIdx < history.length-1){ hIdx++; input.value = history[hIdx]; } e.preventDefault(); }
    else if (e.key === 'ArrowDown'){ if (hIdx > 0){ hIdx--; input.value = history[hIdx]; } else { hIdx=-1; input.value=''; } e.preventDefault(); }
  });

  // welcome banner, fired once boot finishes
  window.__termWelcome = function(){
    if (body.childElementCount) return;
    print("shashank@birthday — stage 01 shell", 'ok');
    print("type 'help' to begin. type 'hint' if you get stuck.", 'dim');
    print("");
  };

  document.getElementById('terminal').addEventListener('click', ()=>input.focus());
})();
