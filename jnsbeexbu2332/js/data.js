/* =========================================================================
 *  data.js  ::  node 02 intercept data
 * ---------------------------------------------------------------------
 *  The decryption hint is emitted to the developer console on load.
 *  (A real engineer checks the console.)
 * ====================================================================== */

// captured ciphertext on this channel.
const INTERCEPT = "QDTYVZFM";          // algo: Vigenère
const DECOY_INTERCEPT = "ZZTOPHITS";   // unrelated capture, ignore

// the next-node address, sealed. opens only with the recovered plaintext key.
const SEALED_PAYLOAD = "756c6a243671772d3e66233c377b756a";

/* emit the decryption hint to the console with some style */
(function consoleClue(){
  const css1 = "color:#00e5ff;font-size:16px;font-weight:bold";
  const css2 = "color:#ffcc00;font-size:13px";
  const css3 = "color:#6a8390;font-size:12px";
  console.log("%c┌─ NODE 02 :: INTERCEPT ───────────────────────────┐", css1);
  console.log("%c  ciphertext : %c" + INTERCEPT, css3, css2);
  console.log("%c  algorithm  : %cVigenère (classic polyalphabetic)", css3, css2);
  console.log("%c  key        : %csee  x-manifest.cipher  in this page's HTML source", css3, css2);
  console.log("%c  → decode the ciphertext, submit the PLAINTEXT as the access key.", css3);
  console.log("%c└──────────────────────────────────────────────────┘", css1);
  console.log("%c(decoys exist. the manifest with region 'ap-south-1' is the live one.)", css3);
})();

/* repeating-key XOR opener (same primitive as node 01) */
function _xorOpen(hexStr, key){
  let out = "";
  for (let i = 0; i < hexStr.length; i += 2){
    const byte = parseInt(hexStr.substr(i, 2), 16);
    out += String.fromCharCode(byte ^ key.charCodeAt((i / 2) % key.length));
  }
  return out;
}
function tryDecrypt(key){
  if (!key) return { ok:false };
  const opened = _xorOpen(SEALED_PAYLOAD, key.toUpperCase());
  if (opened.startsWith("::")){
    const path = opened.slice(2);
    if (/^\/[A-Za-z0-9._-]+\/$/.test(path)) return { ok:true, path };
  }
  return { ok:false, noise: opened };
}
