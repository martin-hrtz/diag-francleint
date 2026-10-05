/* Diag francleint — logique de l'appli.
 * Sur l'écran : window.Diag (pont Java) fournit les vraies données.
 * Dans un navigateur : mode aperçu avec des données d'exemple. */
(function () {
  'use strict';

  var $ = function (s) { return document.querySelector(s); };
  var $$ = function (s) { return Array.prototype.slice.call(document.querySelectorAll(s)); };
  var NATIVE = typeof window.Diag !== 'undefined';
  var DATA = null;
  var ISSUES = [];
  var KEYS = [
    { id: 'vol_up', label: 'Volume +' },
    { id: 'vol_down', label: 'Volume −' },
    { id: 'next', label: 'Suivant' },
    { id: 'prev', label: 'Précédent' },
    { id: 'voice', label: 'Parler' },
    { id: 'call', label: 'Décrocher' },
    { id: 'hangup', label: 'Raccrocher' },
    { id: 'source', label: 'Source' }
  ];
  var KEYRES = {};      // id -> {ok:bool, code, name}
  var KEYLOG = [];      // toutes les touches reçues
  var waiting = null;
  var waitTimer = null;
  var current = 'home';
  var scanned = false;

  var ICONS = {
    screen: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="5" width="18" height="12" rx="2"/><path d="M8 21h8M12 17v4"/></svg>',
    update: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12a9 9 0 1 1-3-6.7M21 4v5h-5"/></svg>',
    memory: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="6" width="16" height="12" rx="2"/><path d="M8 10v4M12 10v4M16 10v4"/></svg>',
    wifi: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5a10 10 0 0 1 14 0M8.5 16a5 5 0 0 1 7 0M12 19.5h.01"/></svg>',
    heat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 14.8V4a2 2 0 0 0-4 0v10.8a4 4 0 1 0 4 0z"/></svg>',
    wheel: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3"/><path d="M12 3v6M4.5 16l5-2.5M19.5 16l-5-2.5"/></svg>',
    ok: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg>'
  };

  /* ---------- navigation ---------- */
  function go(id) {
    if (current === 'wheel' && id !== 'wheel') stopKeyTest();
    current = id;
    $$('.screen').forEach(function (s) { s.classList.toggle('on', s.id === id); });
    if (id === 'wheel') startKeyTest();
  }
  $$('[data-goto]').forEach(function (b) {
    b.addEventListener('click', function () { go(b.getAttribute('data-goto')); });
  });
  window.onBack = function () {
    if (current === 'home') { if (NATIVE) window.Diag.quit(); return; }
    go(scanned ? 'result' : 'home');
  };

  if (!NATIVE) {
    var demo = document.createElement('div');
    demo.textContent = 'Aperçu sur ordinateur · résultats d\'exemple, pas ceux de ta Clio';
    demo.style.cssText = 'position:fixed;left:50%;bottom:.6rem;transform:translateX(-50%);z-index:20;background:#C93A3F;color:#fff;font-weight:800;font-size:.85rem;padding:.45rem 1rem;border-radius:999px;white-space:nowrap';
    document.body.appendChild(demo);
  }

  var h = new Date().getHours();
  $('#hello').textContent = h >= 18 || h < 5 ? 'Bonsoir' : 'Bonjour';

  /* ---------- collecte ---------- */
  function collect() {
    if (NATIVE) {
      try { return JSON.parse(window.Diag.collect()); } catch (e) { return { erreur: String(e) }; }
    }
    // Aperçu navigateur : valeurs d'exemple, jamais utilisées sur l'écran.
    return {
      apercu: true, android_affiche: '12', sdk: 28, android_reel: '9',
      build_time: Date.parse('2020-04-26'), ram_totale: 3.9e9, ram_libre: 1.1e9, ram_faible: false,
      stockage_total: 52e9, stockage_libre: 31e9, wifi: { actif: true, frequence_mhz: 2437 },
      temperatures: [{ zone: 'cpu', valeur: '61000' }],
      applis: [{ id: 'com.autolink.app', nom: 'AutoLink', systeme: true, active: true }]
    };
  }

  /* ---------- analyse ---------- */
  function gb(b) { return (b / 1e9).toFixed(1).replace('.', ',') + ' Go'; }

  function analyse(d) {
    var out = [];
    var shown = parseInt(String(d.android_affiche || '').split('.')[0], 10);
    var real = parseInt(String(d.android_reel || '').split('.')[0], 10);
    if (shown && real && shown !== real) {
      out.push({ lv: 'bad', ico: 'screen', t: 'Ton écran te ment un peu', s: 'Android ' + d.android_reel + ' en vrai, pas ' + d.android_affiche, spot: 'screen' });
    } else if (real) {
      out.push({ lv: 'ok', ico: 'ok', t: 'Version Android honnête', s: 'Android ' + d.android_reel });
    }

    if (d.build_time) {
      var y = new Date(d.build_time).getFullYear();
      var age = (Date.now() - d.build_time) / 31557600000;
      if (age > 3) out.push({ lv: 'warn', ico: 'update', t: 'Jamais mis à jour', s: 'Son système date de ' + y, spot: 'screen' });
    }

    if (d.ram_totale) {
      var ratio = d.ram_libre / d.ram_totale;
      if (d.ram_faible || ratio < 0.2) out.push({ lv: 'bad', ico: 'memory', t: 'Mémoire saturée', s: gb(d.ram_libre) + ' libres sur ' + gb(d.ram_totale), spot: 'screen' });
      else out.push({ lv: 'ok', ico: 'ok', t: 'Mémoire OK', s: gb(d.ram_libre) + ' libres sur ' + gb(d.ram_totale) });
    }

    if (d.stockage_total && d.stockage_libre / d.stockage_total < 0.15) {
      out.push({ lv: 'warn', ico: 'memory', t: 'Stockage presque plein', s: gb(d.stockage_libre) + ' libres', spot: 'screen' });
    }

    var f = d.wifi && d.wifi.frequence_mhz;
    if (f > 2300 && f < 2600) out.push({ lv: 'warn', ico: 'wifi', t: 'Wi-Fi en 2,4 GHz', s: 'CarPlay sans fil peut saccader' });
    else if (f > 4900) out.push({ lv: 'ok', ico: 'ok', t: 'Wi-Fi en 5 GHz', s: 'Idéal pour CarPlay sans fil' });

    var maxT = 0;
    (d.temperatures || []).forEach(function (z) {
      var v = parseFloat(z.valeur); if (v > 1000) v = v / 1000; if (v > maxT && v < 150) maxT = v;
    });
    if (maxT > 75) out.push({ lv: 'warn', ico: 'heat', t: 'Ça chauffe', s: Math.round(maxT) + ' °C mesurés' });

    var cp = (d.applis || []).filter(function (a) { return /autolink|zlink|carlink|tlink|autokit|carplay/i.test(a.id + ' ' + (a.nom || '')); });
    if (cp.length) out.push({ lv: 'ok', ico: 'ok', t: 'CarPlay trouvé', s: (cp[0].nom || cp[0].id) + ' est bien là' });
    else out.push({ lv: 'warn', ico: 'wifi', t: 'Appli CarPlay introuvable', s: 'À vérifier dans le rapport' });

    var tested = Object.keys(KEYRES);
    var dead = tested.filter(function (k) { return !KEYRES[k].ok; });
    if (dead.length) {
      out.push({ lv: 'bad', ico: 'wheel', t: dead.length + (dead.length > 1 ? ' boutons boudent' : ' bouton boude'), s: dead.map(function (k) { return label(k); }).join(', '), spot: 'wheel' });
    } else if (tested.length) {
      out.push({ lv: 'ok', ico: 'ok', t: 'Volant OK', s: tested.length + ' boutons testés' });
    }

    var order = { bad: 0, warn: 1, ok: 2 };
    out.sort(function (a, b) { return order[a.lv] - order[b.lv]; });
    return out;
  }
  function label(id) { for (var i = 0; i < KEYS.length; i++) if (KEYS[i].id === id) return KEYS[i].label; return id; }

  function renderResult() {
    ISSUES = analyse(DATA);
    var n = ISSUES.filter(function (i) { return i.lv !== 'ok'; }).length;
    $('#count').textContent = n;
    $('#countTxt').innerHTML = n === 0 ? 'rien à régler,<br>tout roule' : (n === 1 ? 'petit truc à régler' : 'petits trucs à régler') + (n <= 3 ? ',<br>rien de grave' : '');
    $('#verdict').innerHTML = n === 0 ? 'Ta Clio est au <em>top</em>.' : n <= 3 ? 'Ta Clio est en <em>forme</em>.' : 'Ta Clio a besoin d\'un <em>coup de main</em>.';
    var tags = { bad: 'À régler', warn: 'À surveiller', ok: 'Nickel' };
    $('#rows').innerHTML = ISSUES.slice(0, 5).map(function (i) {
      return '<div class="row lv-' + i.lv + '"><span class="ico">' + ICONS[i.ico] + '</span><div class="txt"><b>' + i.t + '</b><small>' + i.s + '</small></div><span class="tag">' + tags[i.lv] + '</span></div>';
    }).join('');
    $('#spotScreen').classList.toggle('on', ISSUES.some(function (i) { return i.spot === 'screen' && i.lv !== 'ok'; }));
    $('#spotWheel').classList.toggle('on', ISSUES.some(function (i) { return i.spot === 'wheel'; }));
  }

  /* ---------- check-up ---------- */
  $('#go').addEventListener('click', function () {
    go('scan');
    var steps = $$('.step');
    var titles = ['On regarde ton écran.', 'On fait le tour.', 'Encore une petite minute.', 'Presque fini.'];
    steps.forEach(function (s) { s.className = 'step'; var t = s.querySelector('.tag'); if (t) t.remove(); });
    var i = 0;
    function tick() {
      if (i > 0) {
        steps[i - 1].className = 'step done';
        var old = steps[i - 1].querySelector('.tag'); if (old) old.remove();
      }
      if (i === 1 && !DATA) DATA = collect();
      if (i < steps.length) {
        steps[i].className = 'step now';
        var tag = document.createElement('span'); tag.className = 'tag'; tag.textContent = 'en cours';
        steps[i].appendChild(tag);
        $('#scanTitle').textContent = titles[i];
      }
      var p = Math.round(i / steps.length * 100);
      $('#pct').textContent = p;
      $('#bar').style.transform = 'scaleX(' + p / 100 + ')';
      i++;
      if (i <= steps.length) setTimeout(tick, 900);
      else { if (!DATA) DATA = collect(); scanned = true; renderResult(); setTimeout(function () { go('result'); }, 500); }
    }
    DATA = null;
    tick();
  });

  /* ---------- test du volant ---------- */
  function renderKeys() {
    $('#keys').innerHTML = KEYS.map(function (k) {
      var r = KEYRES[k.id];
      var cls = waiting === k.id ? 'wait' : r ? (r.ok ? 'got' : 'none') : '';
      var txt = waiting === k.id ? 'Appuie sur ton volant…' : r ? (r.ok ? 'Ça marche' : 'Rien reçu') : 'À tester';
      return '<button class="key ' + cls + '" data-k="' + k.id + '"><b>' + k.label + '</b><small>' + txt + '</small></button>';
    }).join('');
  }
  $('#keys').addEventListener('click', function (e) {
    var b = e.target.closest('.key'); if (!b) return;
    waiting = b.getAttribute('data-k');
    clearTimeout(waitTimer);
    waitTimer = setTimeout(function () {
      if (waiting) { KEYRES[waiting] = { ok: false }; waiting = null; renderKeys(); }
    }, 8000);
    renderKeys();
  });
  window.onCarKey = function (code, name, scan) {
    KEYLOG.push({ code: code, nom: name, scan: scan, quand: new Date().toISOString() });
    $('#last').textContent = 'Dernière touche reçue : ' + name + ' (' + code + ')';
    if (waiting) {
      KEYRES[waiting] = { ok: true, code: code, nom: name };
      waiting = null; clearTimeout(waitTimer); renderKeys();
    }
  };
  // Aperçu navigateur : le clavier simule le volant.
  if (!NATIVE) document.addEventListener('keydown', function (e) { if (current === 'wheel') window.onCarKey(e.keyCode, e.key, 0); });

  function startKeyTest() { renderKeys(); if (NATIVE) window.Diag.keyTest(true); }
  function stopKeyTest() { waiting = null; clearTimeout(waitTimer); if (NATIVE) window.Diag.keyTest(false); }
  $('#wheelDone').addEventListener('click', function () {
    if (DATA) { renderResult(); go('result'); } else go('home');
  });

  /* ---------- export ---------- */
  function report() {
    var L = [];
    L.push('RAPPORT DIAG FRANCLEINT');
    L.push('Date : ' + new Date().toLocaleString('fr-FR'));
    L.push('');
    L.push('== VERDICT ==');
    ISSUES.forEach(function (i) { L.push('[' + i.lv.toUpperCase() + '] ' + i.t + ' — ' + i.s); });
    L.push('');
    L.push('== VOLANT ==');
    KEYS.forEach(function (k) {
      var r = KEYRES[k.id];
      L.push(k.label + ' : ' + (r ? (r.ok ? 'OK ' + r.nom + ' (' + r.code + ')' : 'RIEN REÇU') : 'non testé'));
    });
    L.push('Touches reçues : ' + JSON.stringify(KEYLOG));
    L.push('');
    L.push('== DONNÉES BRUTES ==');
    L.push(JSON.stringify(DATA, null, 2));
    return L.join('\n');
  }
  function toast(t) {
    $('#toastTxt').textContent = t;
    var el = $('#toast'); el.classList.add('on');
    clearTimeout(el._t); el._t = setTimeout(function () { el.classList.remove('on'); }, 3500);
  }
  $('#export').addEventListener('click', function () {
    var text = report();
    if (NATIVE) {
      var r = {};
      try { r = JSON.parse(window.Diag.export(text)); } catch (e) { r = { ok: [] }; }
      var usb = (r.ok || []).filter(function (p) { return p.indexOf('/Download') < 0; }).length;
      if (r.ok && r.ok.length) toast('Rapport enregistré dans Téléchargements' + (usb ? ' + clé USB' : ''));
      else toast('Impossible d\'enregistrer : branche une clé USB et réessaie');
    } else {
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
      a.download = 'diag-francleint-apercu.txt'; a.click();
      toast('Aperçu : rapport d\'exemple téléchargé');
    }
  });
})();
