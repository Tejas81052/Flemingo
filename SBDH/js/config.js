/* =========================================================================
 *  config.js  ::  runtime constants for node SBDH-01
 *  ---------------------------------------------------------------------
 *  NOTE: values below are loaded at boot. do not ship debug tokens.
 * ====================================================================== */

// channel signal table — partial. (the rest is distributed.)
const SIG_2   = "TO";          // fragment 3 of 3
const _legacy = "redrah yrt";  // deprecated session token (unused)
const _nonce  = "9f14e0";      // request nonce, rotates hourly

/* The next node's address is NOT stored in plaintext anywhere on this page.
 * It is sealed. Forge the correct key from the three scattered fragments,
 * then run:  decrypt <key>
 * A wrong key yields noise. */
const SEALED_PAYLOAD = "747f60243c3c2d312b3d2d3b607c7c6661";

/* repeating-key XOR — the only primitive you need. */
function _xorOpen(hexStr, key){
  let out = "";
  for (let i = 0; i < hexStr.length; i += 2){
    const byte = parseInt(hexStr.substr(i, 2), 16);
    out += String.fromCharCode(byte ^ key.charCodeAt((i / 2) % key.length));
  }
  return out;
}

/* attempt to open the sealed payload with a candidate key.
 * valid plaintext is prefixed with "::"  — anything else is rejected. */
function tryDecrypt(key){
  if (!key) return { ok:false };
  const opened = _xorOpen(SEALED_PAYLOAD, key);
  if (opened.startsWith("::")){
    const path = opened.slice(2);                 // -> "/jnsbeexbu2332/"
    // the WHOLE path must be well-formed; a partial/wrong key garbles the tail
    if (/^\/[A-Za-z0-9._-]+\/$/.test(path)) return { ok:true, path };
  }
  return { ok:false, noise: opened };
}
