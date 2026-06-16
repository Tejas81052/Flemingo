/* matrix rain — node 02 (cyan) */
(function(){
  const c = document.getElementById('matrix'); if(!c) return;
  const ctx = c.getContext('2d');
  let w,h,cols,drops;
  const g = "01<>/{}[]=+*アサタABCDEF0123456789".split("");
  function resize(){ w=c.width=innerWidth; h=c.height=innerHeight; cols=Math.floor(w/16);
    drops=Array(cols).fill(0).map(()=>Math.random()*-h); }
  resize(); addEventListener('resize',resize);
  (function draw(){
    ctx.fillStyle="rgba(4,7,10,0.08)"; ctx.fillRect(0,0,w,h);
    ctx.font="15px monospace";
    for(let i=0;i<cols;i++){
      ctx.fillStyle=Math.random()>0.975?"#d6f4ff":"#00e5ff";
      ctx.fillText(g[(Math.random()*g.length)|0], i*16, drops[i]);
      drops[i]=drops[i]>h&&Math.random()>0.975?0:drops[i]+16;
    }
    requestAnimationFrame(draw);
  })();
})();
