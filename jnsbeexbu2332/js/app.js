/* =========================================================================
 *  app.js  ::  node 02 decryptor UI
 * ====================================================================== */
(function(){
  const input  = document.getElementById('keyInput');
  const btn     = document.getElementById('keyBtn');
  const msg     = document.getElementById('dzMsg');
  const reveal  = document.getElementById('dzReveal');

  function attempt(){
    const key = (input.value || '').trim();
    msg.className = 'dz-msg';
    if (!key){ msg.textContent = "enter the decrypted key."; msg.classList.add('err'); return; }

    const res = tryDecrypt(key);
    if (res.ok){
      msg.textContent = "✔ key accepted — seal broken.";
      msg.classList.add('ok');
      reveal.classList.remove('hidden');
      reveal.innerHTML =
        `<div style="color:var(--neon)">next node: <strong>${res.path}</strong></div>` +
        `<a href="${res.path}">ENTER NODE 03 &nbsp;➜</a>`;
      btn.disabled = true; input.disabled = true;
      reveal.scrollIntoView({behavior:'smooth', block:'center'});
    } else {
      msg.textContent = "✗ ACCESS DENIED — that key produced noise. (decode the intercept first; check the console.)";
      msg.classList.add('err');
      input.classList.add('shake');
      setTimeout(()=>input.classList.remove('shake'), 500);
    }
  }

  btn.addEventListener('click', attempt);
  input.addEventListener('keydown', e=>{ if(e.key==='Enter') attempt(); });

  // tiny shake animation injected
  const s = document.createElement('style');
  s.textContent = "@keyframes shk{0%,100%{transform:translateX(0)}25%{transform:translateX(-6px)}75%{transform:translateX(6px)}}.shake{animation:shk .3s}";
  document.head.appendChild(s);
})();
