javascript:(async function(){
  try{
    const sleep=ms=>new Promise(r=>setTimeout(r,ms));
    const norm=s=>String(s||'').replace(/\u00a0/g,' ').replace(/[ \t]+/g,' ').replace(/\n[ \t]+/g,'\n').replace(/\n{3,}/g,'\n\n').trim();
    const lower=s=>norm(s).toLocaleLowerCase('es');
    const text=e=>norm(e?.innerText||e?.textContent||'');
    const page=location.href;
    let body=norm(document.body?.innerText||'');
    const challengeRx=/captcha|completa el captcha|haz click para continuar|¡?ups!?\s*algo se detuvo|verifica(?:r)? que eres humano|comprueba que eres humano|unusual traffic|demasiadas solicitudes|request blocked|access denied|forbidden/i;
    if(challengeRx.test(body)||body.length<180){AndroidBridge.onCaptcha();return;}

    const buttons=[...document.querySelectorAll('button,a,[role="button"]')];
    const accept=buttons.find(e=>/^(aceptar|aceptar todo|acepto|consentir|permitir todas|continuar)$/i.test(text(e))||/aceptar.*cookies|aceptar.*todo/i.test(text(e)));
    if(accept){try{accept.click();await sleep(450);}catch{}}
    const phoneBtns=[...document.querySelectorAll('button,a,[role="button"]')].filter(e=>/ver\s*(?:el\s*)?(?:tel[eé]fono|n[uú]mero)|mostrar\s*(?:el\s*)?(?:tel[eé]fono|n[uú]mero)|ver contacto/i.test(text(e))).slice(0,30);
    for(const b of phoneBtns){try{b.click();await sleep(180);}catch{}}
    const max=Math.max(document.body?.scrollHeight||0,document.documentElement?.scrollHeight||0);
    for(const f of [.25,.55,.82,1]){window.scrollTo(0,Math.floor(max*f));await sleep(350);}
    window.scrollTo(0,0);await sleep(650);

    body=norm(document.body?.innerText||'');
    if(challengeRx.test(body)){AndroidBridge.onCaptcha();return;}

    const serviceRx=/limpiez|hogar|dom[eé]stic|interna|externa|cuidad|mayor|niñ|plancha|cocina|emplead(?:a|o)?\s+de\s+hogar|asistent|ayuda\s+a\s+domicilio|casas?|pisos?|oficinas?|comunidades?/i;
    const seekerRx=/busco\s+(?:empleo|trabajo)|buscando\s+(?:empleo|trabajo)|me\s+ofrezco|se\s+ofrece|ofrezco\s+mis\s+servicios|busco\s+trabajar|disponible\s+para\s+trabajar|realizo\s+(?:trabajos|servicios)|trabajo\s+por\s+horas/i;
    const navRx=/^(milanuncios|servicio dom[eé]stico|ofertas de empleo|buscar|filtros?|ordenar|iniciar sesi[oó]n|entra en tu cuenta|publicar anuncio|lo m[aá]s buscado)$/i;
    const phones=s=>[...new Set([...String(s||'').matchAll(/(?<!\d)(?:\+34[\s.-]*)?(?:[6789]\d{2})(?:[\s.-]*\d{3}){2}(?!\d)/g)].map(m=>m[0].replace(/\s+/g,' ').trim()))];
    const imageUrls=root=>{const out=new Set();const add=u=>{try{if(!u)return;const x=new URL(String(u).trim(),location.href);if(x.protocol!=='https:')return;const h=x.hostname.toLowerCase();if(!(h==='images.milanuncios.com'||h.endsWith('.milanuncios.com')))return;if(/logo|sprite|icon|placeholder|avatar-default|favicon/i.test(x.pathname))return;out.add(x.href);}catch{}};const srcset=s=>{if(!s)return;const ps=String(s).split(',').map(x=>x.trim().split(/\s+/)[0]).filter(Boolean);if(ps.length)add(ps[ps.length-1]);};
      for(const im of root.querySelectorAll?.('img')||[]){add(im.currentSrc||im.src);for(const k of ['data-src','data-original','data-lazy-src','data-image'])add(im.getAttribute(k)||'');srcset(im.getAttribute('srcset'));}
      for(const so of root.querySelectorAll?.('picture source,source[srcset]')||[])srcset(so.getAttribute('srcset'));
      return [...out].slice(0,5);
    };
    const locate=s=>{s=norm(s);let city='',province='';const m=s.match(/(?:^|\n)([^\n()]{2,60})\s*\(([^\n()]{2,45})\)(?:\n|$)/m);if(m){city=norm(m[1]);province=norm(m[2]);}return{city,province};};
    const dateText=s=>{const m=String(s||'').match(/(?:Hace\s+\d+\s+(?:min(?:uto)?s?|h(?:ora)?s?|d[ií]as?|semanas?|meses?)|Hoy|Ayer|Destacado|Nuevo)/i);return m?m[0]:'';};
    const priceText=s=>{const m=String(s||'').match(/\b\d{1,4}(?:[.,]\d{1,2})?\s*€(?:\s*(?:\/|por)\s*(?:h|hora))?/i);return m?m[0]:'';};
    const titleFrom=el=>{for(const s of ['h2','h3','h4','[role="heading"]','[data-testid*="title" i]','[class*="title" i]']){const n=el.querySelector?.(s);const t=norm(n?.innerText||n?.textContent||'');if(t.length>=3&&t.length<=190&&!navRx.test(t))return t;}const a=[...(el.querySelectorAll?.('a[href]')||[])].map(x=>norm(x.innerText||x.textContent||'')).find(t=>t.length>=3&&t.length<=190&&!navRx.test(t));return a||'';};
    const linkFrom=el=>{let best=page,bestScore=-1;for(const a of el.querySelectorAll?.('a[href]')||[]){try{const u=new URL(a.href,location.href);if(!(u.hostname==='milanuncios.com'||u.hostname.endsWith('.milanuncios.com')))continue;if(/(?:\/ayuda|\/legal|\/contacto|\/login|\/registro|\/publicar|\/mis-anuncios)/i.test(u.pathname))continue;u.hash='';const t=norm(a.innerText||a.textContent||'');let score=0;if(/\.htm$/i.test(u.pathname))score+=10;if(t.length>=5&&!navRx.test(t))score+=5;if(u.pathname!==location.pathname)score+=3;if((u.pathname.match(/\//g)||[]).length>=2)score+=2;if(score>bestScore){bestScore=score;best=u.href;}}catch{}}return best;};
    const records=[];const seen=new Set();
    const push=r=>{r.title=norm(r.title);r.description=norm(r.description);r.visible_text=norm(r.visible_text||r.description);if(r.title.length<3||r.title.length>190||navRx.test(r.title))return;if(r.description.length<18&&!serviceRx.test(r.title))return;const h=r.title+' '+r.description;if(!serviceRx.test(h)||!seekerRx.test(h))return;const loc=locate(r.visible_text);r.city=norm(r.city||loc.city);r.province=norm(r.province||loc.province);r.phones=[...new Set([...(r.phones||[]),...phones(r.visible_text)])];r.images=[...new Set(r.images||[])].slice(0,5);r.url=r.url||page;r.date=r.date||dateText(r.visible_text);r.price=r.price||priceText(r.visible_text);const stable=r.description.replace(/Hace\s+\d+\s+(?:min(?:uto)?s?|h(?:ora)?s?|d[ií]as?|semanas?|meses?)|\bHoy\b|\bAyer\b|\bDestacado\b|\bNuevo\b/gi,' ').replace(/\s+/g,' ').trim();r.card_key=norm(r.card_key||[r.title,r.city,r.province,r.phones[0]||'',stable.slice(0,700)].join('|')).slice(0,1600);const k=lower(r.card_key);if(seen.has(k))return;seen.add(k);records.push(r);};

    const selectors=['article','[data-testid*="ad-card" i]','[data-testid*="listing-card" i]','[data-testid*="result-card" i]','[class*="adcard" i]','[class*="ad-card" i]','[class*="listing-card" i]','[class*="result-card" i]','[class*="ad-item" i]','[class*="listing-item" i]'];
    const els=[];for(const s of selectors){for(const e of document.querySelectorAll(s)){if(!els.includes(e))els.push(e);}}
    const dateMarkRx=/(?:Hace\s+\d+\s+(?:min(?:uto)?s?|h(?:ora)?s?|d[ií]as?|semanas?|meses?)|\bHoy\b|\bAyer\b|\bDestacado\b|\bNuevo\b)/gi;
    const cardLooksAtomic=(el,t)=>{
      if(!el||t.length<28||t.length>2400)return false;
      const marks=t.match(dateMarkRx)||[];
      if(marks.length>1)return false;
      const heads=[...el.querySelectorAll?.('h2,h3,h4,[role="heading"],[data-testid*="title" i]')||[]].filter(x=>{const z=norm(x.innerText||x.textContent||'');return z.length>=3&&z.length<=190&&!navRx.test(z);});
      if(heads.length>2)return false;
      const adLinks=[...(el.querySelectorAll?.('a[href]')||[])].filter(a=>{try{return /\.htm$/i.test(new URL(a.href,location.href).pathname);}catch{return false;}});
      if(adLinks.length>2)return false;
      return true;
    };
    for(const el of els){const t=norm(el.innerText||el.textContent||'');if(!cardLooksAtomic(el,t)||!serviceRx.test(t)||!seekerRx.test(t))continue;const title=titleFrom(el);if(!title||!serviceRx.test(title+' '+t))continue;const imgs=imageUrls(el);const shape=imgs.length||/\([^\n()]{2,45}\)/.test(t)||/Hace\s+\d+|Hoy|Ayer|Destacado|Nuevo/i.test(t);if(!shape)continue;push({url:linkFrom(el),title,description:t,visible_text:t,images:imgs,phones:phones(t)});}

    for(const h of document.querySelectorAll('h2,h3,h4,[role="heading"]')){const title=norm(h.innerText||h.textContent||'');if(title.length<3||title.length>190||navRx.test(title))continue;let el=h.parentElement,best=null;for(let i=0;i<5&&el&&el!==document.body;i++,el=el.parentElement){const t=norm(el.innerText||el.textContent||'');if(!cardLooksAtomic(el,t))continue;if(t.length>=35&&serviceRx.test(title+' '+t)&&seekerRx.test(title+' '+t)){best=el;break;}}if(best){const t=norm(best.innerText||best.textContent||'');push({url:linkFrom(best),title,description:t,visible_text:t,images:imageUrls(best),phones:phones(t)});}}

    const diagnostics={bodyChars:body.length,domCandidates:els.length,headings:document.querySelectorAll('h2,h3,h4,[role="heading"]').length,challenge:false,url:page};
    AndroidBridge.onExtract(JSON.stringify({records,diagnostics}));
  }catch(e){AndroidBridge.onJsError(String(e&&e.message||e));}
})();
