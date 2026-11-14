/**
 * Jelly Loc — Mobile PWA & GPS Emulation Engine
 * Interactive Leaflet Map, Nominatim Search, Virtual Joystick,
 * Wake Lock API, Haptic Feedback, and Mobile Device Integrations.
 *
 * The browser is the source of truth for all state (persisted in localStorage).
 * The server is synced best-effort so /api/location, /api/hook.js and the iOS
 * shortcut endpoint can read it — on Vercel serverless, server memory is not
 * reliable between requests, so the UI never depends on it.
 */

(() => {
  'use strict';

  const STORAGE_KEY = 'jellyloc:v1';

  // Application State
  const state = {
    isMocking: false,
    selectedPlace: {
      name: 'Charminar, Hyderabad',
      address: 'Hyderabad, Telangana, India',
      latitude: 17.361563,
      longitude: 78.474665,
    },
    effectiveCoords: { latitude: 17.361563, longitude: 78.474665 },
    accuracy: 7.0,
    altitude: 505.0,
    jitterEnabled: true,
    jitterMeters: 4.5,
    simSpeedKmh: 5.0,
    favorites: [],
    recents: [],
    wakeLock: null,
    joystickOpen: false,
    joystickInterval: null,
    joystickVector: { x: 0, y: 0 },
    deferredPrompt: null,
  };

  const $ = (id) => document.getElementById(id);
  let el = {};
  let mapInstance = null;
  let mockMarker = null;
  let accuracyCircle = null;
  let searchTimer = null;
  let searchSeq = 0;
  let jitterTimer = null;
  let moveSyncTimer = null;

  // --- Init ---
  function init() {
    el = {
      statusPill: $('status-pill'),
      statusText: $('status-text'),
      searchInput: $('search-input'),
      searchSpinner: $('search-spinner'),
      searchClear: $('search-clear'),
      suggestionsCard: $('suggestions-card'),
      placeTitle: $('place-title'),
      placeCoords: $('place-coords'),
      btnCopyCoords: $('btn-copy-coords'),
      btnFavorite: $('btn-favorite'),
      favoriteIcon: $('favorite-icon'),
      telemetryLat: $('telemetry-lat'),
      telemetryLon: $('telemetry-lon'),
      telemetryAcc: $('telemetry-acc'),
      telemetrySpeed: $('telemetry-speed'),
      btnSetMock: $('btn-set-mock'),
      btnSetMockText: $('btn-set-mock-text'),
      chipsContainer: $('chips-container'),
      btnLocateMe: $('btn-locate-me'),
      btnToggleJoystick: $('btn-toggle-joystick'),
      btnOpenGuide: $('btn-open-guide'),
      btnOpenSettings: $('btn-open-settings'),
      btnInstallPwa: $('btn-install-pwa'),
      joystickPanel: $('joystick-panel'),
      joystickBase: $('joystick-base'),
      joystickNub: $('joystick-nub'),
      guideModal: $('guide-modal'),
      settingsModal: $('settings-modal'),
      toast: $('toast'),
      settingJitter: $('setting-jitter'),
      settingJitterMeters: $('setting-jitter-meters'),
      settingJitterVal: $('setting-jitter-val'),
      shortcutUrl: $('shortcut-url'),
    };

    loadState();
    applyUrlParams();

    if (typeof L === 'undefined') {
      showToast('Map library failed to load — check your connection');
    } else {
      initLeafletMap();
    }
    setupEventListeners();
    setupJoystick();
    setupPwa();
    syncSettingsUI();
    updateUI();
    startJitterLoop();

    if (state.isMocking) {
      requestWakeLock();
      syncServer(false);
    }
  }

  // --- Persistence ---
  function loadState() {
    let saved = null;
    try {
      saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || 'null');
    } catch (e) { /* storage unavailable */ }
    if (!saved) return;

    if (isValidPlace(saved.selectedPlace)) state.selectedPlace = saved.selectedPlace;
    if (Array.isArray(saved.favorites)) state.favorites = saved.favorites.filter(isValidPlace);
    if (Array.isArray(saved.recents)) state.recents = saved.recents.filter(isValidPlace);
    if (typeof saved.isMocking === 'boolean') state.isMocking = saved.isMocking;
    if (typeof saved.jitterEnabled === 'boolean') state.jitterEnabled = saved.jitterEnabled;
    if (typeof saved.jitterMeters === 'number') state.jitterMeters = saved.jitterMeters;
    if (typeof saved.simSpeedKmh === 'number') state.simSpeedKmh = saved.simSpeedKmh;
    state.effectiveCoords = { latitude: state.selectedPlace.latitude, longitude: state.selectedPlace.longitude };
  }

  function saveState() {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        selectedPlace: state.selectedPlace,
        favorites: state.favorites,
        recents: state.recents,
        isMocking: state.isMocking,
        jitterEnabled: state.jitterEnabled,
        jitterMeters: state.jitterMeters,
        simSpeedKmh: state.simSpeedKmh,
      }));
    } catch (e) { /* storage unavailable */ }
  }

  function isValidPlace(p) {
    return p && typeof p.latitude === 'number' && typeof p.longitude === 'number'
      && Math.abs(p.latitude) <= 90 && Math.abs(p.longitude) <= 180;
  }

  // Manifest shortcuts & shared links: /?lat=..&lng=..&name=..
  function applyUrlParams() {
    const params = new URLSearchParams(window.location.search);
    const lat = parseFloat(params.get('lat'));
    const lng = parseFloat(params.get('lng') ?? params.get('lon'));
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180) return;
    const name = params.get('name') || `${lat.toFixed(5)}, ${lng.toFixed(5)}`;
    state.selectedPlace = { name, address: `${lat.toFixed(6)}, ${lng.toFixed(6)}`, latitude: lat, longitude: lng };
    state.effectiveCoords = { latitude: lat, longitude: lng };
    saveState();
    history.replaceState(null, '', window.location.pathname);
  }

  // --- Geo math ---
  function destination(lat, lon, bearingDeg, distM) {
    const R = 6378137.0;
    const φ1 = lat * Math.PI / 180;
    const λ1 = lon * Math.PI / 180;
    const θ = bearingDeg * Math.PI / 180;
    const δ = distM / R;
    const φ2 = Math.asin(Math.sin(φ1) * Math.cos(δ) + Math.cos(φ1) * Math.sin(δ) * Math.cos(θ));
    let λ2 = λ1 + Math.atan2(Math.sin(θ) * Math.sin(δ) * Math.cos(φ1), Math.cos(δ) - Math.sin(φ1) * Math.sin(φ2));
    λ2 = ((λ2 * 180 / Math.PI + 540) % 360) - 180; // normalise to [-180, 180)
    return { latitude: φ2 * 180 / Math.PI, longitude: λ2 };
  }

  function gaussian() {
    let u = 0, v = 0;
    while (u === 0) u = Math.random();
    while (v === 0) v = Math.random();
    return Math.sqrt(-2.0 * Math.log(u)) * Math.cos(2.0 * Math.PI * v);
  }

  // --- Leaflet Map ---
  function initLeafletMap() {
    const { latitude: lat, longitude: lng } = state.selectedPlace;

    mapInstance = L.map('map', { center: [lat, lng], zoom: 15, zoomControl: false, attributionControl: false });

    // Esri Dark Gray Canvas — free, no API key required.
    // Native tiles stop at z16; maxZoom 19 lets Leaflet upscale for street-level pin placement.
    L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/{z}/{y}/{x}',
      { maxZoom: 19, maxNativeZoom: 16 }
    ).addTo(mapInstance);
    L.tileLayer(
      'https://server.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Reference/MapServer/tile/{z}/{y}/{x}',
      { maxZoom: 19, maxNativeZoom: 16, opacity: 0.8 }
    ).addTo(mapInstance);

    const customIcon = L.divIcon({
      className: 'jelly-marker',
      html: '<div class="jelly-marker-pulse"></div><div class="jelly-marker-pin"></div>',
      iconSize: [44, 44],
      iconAnchor: [22, 42],
    });

    mockMarker = L.marker([lat, lng], { icon: customIcon, draggable: true }).addTo(mapInstance);
    accuracyCircle = L.circle([lat, lng], {
      radius: state.accuracy,
      color: '#00f5d4',
      weight: 1,
      fillColor: '#00f5d4',
      fillOpacity: 0.12,
    }).addTo(mapInstance);

    mockMarker.on('dragend', (e) => {
      const pos = e.target.getLatLng();
      haptic(15);
      selectCoordinates(pos.lat, pos.lng, 'Pinned location');
    });

    mapInstance.on('click', (e) => {
      haptic(15);
      selectCoordinates(e.latlng.lat, e.latlng.lng, 'Dropped pin');
    });
  }

  function moveMarker(lat, lng, pan) {
    if (mockMarker) mockMarker.setLatLng([lat, lng]);
    if (accuracyCircle) {
      accuracyCircle.setLatLng([lat, lng]);
      accuracyCircle.setRadius(state.accuracy);
    }
    if (pan && mapInstance) mapInstance.panTo([lat, lng], { animate: true, duration: 0.5 });
  }

  // --- Jitter loop: simulated satellite drift while mocking ---
  function startJitterLoop() {
    clearInterval(jitterTimer);
    jitterTimer = setInterval(() => {
      if (!state.isMocking || !state.jitterEnabled) return;
      const dist = Math.abs(gaussian() * (state.jitterMeters / 2));
      const p = destination(state.selectedPlace.latitude, state.selectedPlace.longitude, Math.random() * 360, dist);
      state.effectiveCoords = p;
      state.accuracy = Math.round(Math.max(3, Math.min(14, 7 + (Math.random() * 5 - 2.5))) * 10) / 10;
      moveMarker(p.latitude, p.longitude, false);
      updateTelemetryUI();
    }, 1000);
  }

  // --- Server sync (best-effort; UI never waits on it) ---
  function placePayload(recordRecent) {
    return {
      latitude: state.selectedPlace.latitude,
      longitude: state.selectedPlace.longitude,
      name: state.selectedPlace.name,
      address: state.selectedPlace.address,
      jitter: state.jitterEnabled,
      jitter_meters: state.jitterMeters,
      speed_kmh: state.simSpeedKmh,
      altitude: state.altitude,
      record_recent: recordRecent,
    };
  }

  function syncServer(recordRecent) {
    if (!state.isMocking) return Promise.resolve(true);
    return fetch('/api/mock/set', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(placePayload(recordRecent)),
    }).then((r) => r.ok).catch(() => false);
  }

  // Throttled sync for continuous movement (joystick / keyboard)
  function scheduleMoveSync() {
    if (moveSyncTimer) return;
    moveSyncTimer = setTimeout(() => {
      moveSyncTimer = null;
      syncServer(false);
    }, 800);
  }

  // --- UI ---
  function updateUI() {
    const { latitude: lat, longitude: lng } = state.selectedPlace;

    el.placeTitle.textContent = state.selectedPlace.name || 'Selected Location';
    el.placeCoords.textContent = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;

    if (!state.isMocking) {
      state.effectiveCoords = { latitude: lat, longitude: lng };
      state.accuracy = 7.0;
    }
    moveMarker(lat, lng, true);

    const shortName = (state.selectedPlace.name || '').split(',')[0];
    el.statusPill.classList.toggle('active', state.isMocking);
    el.btnSetMock.classList.toggle('mocking', state.isMocking);
    el.statusText.textContent = state.isMocking ? `Mocking: ${shortName}` : 'Idle';
    el.btnSetMockText.textContent = state.isMocking ? 'Stop Mocking' : 'Set Location';

    const isFav = state.favorites.some((f) => isSameCoord(f.latitude, f.longitude, lat, lng));
    el.btnFavorite.classList.toggle('active', isFav);
    el.favoriteIcon.textContent = isFav ? '♥' : '♡';

    if (el.shortcutUrl) {
      el.shortcutUrl.textContent = `${window.location.origin}/api/ios/shortcut?lat=${lat.toFixed(6)}&lon=${lng.toFixed(6)}&name=${encodeURIComponent(state.selectedPlace.name || '')}`;
    }

    updateTelemetryUI();
    renderChips();
    saveState();
  }

  function updateTelemetryUI() {
    const { latitude: lat, longitude: lon } = state.effectiveCoords;
    if (el.telemetryLat) el.telemetryLat.textContent = lat.toFixed(5);
    if (el.telemetryLon) el.telemetryLon.textContent = lon.toFixed(5);
    if (el.telemetryAcc) el.telemetryAcc.textContent = `±${state.accuracy.toFixed(1)}m`;
    if (el.telemetrySpeed) el.telemetrySpeed.textContent = `${state.simSpeedKmh.toFixed(0)} km/h`;
  }

  function syncSettingsUI() {
    if (el.settingJitter) el.settingJitter.checked = state.jitterEnabled;
    if (el.settingJitterMeters) el.settingJitterMeters.value = String(state.jitterMeters);
    if (el.settingJitterVal) el.settingJitterVal.textContent = `±${state.jitterMeters}m`;
    document.querySelectorAll('.speed-chip').forEach((b) => {
      b.classList.toggle('selected', parseFloat(b.dataset.speed) === state.simSpeedKmh);
    });
  }

  function makeChip(icon, place, extraClass) {
    const chip = document.createElement('button');
    chip.type = 'button';
    chip.className = `quick-chip${extraClass ? ' ' + extraClass : ''}`;
    const i = document.createElement('span');
    i.textContent = icon;
    const label = document.createElement('span');
    label.textContent = (place.name || 'Place').split(',')[0];
    chip.append(i, label);
    chip.onclick = () => selectPlace(place);
    return chip;
  }

  function renderChips() {
    el.chipsContainer.replaceChildren();
    state.favorites.forEach((fav) => el.chipsContainer.appendChild(makeChip('★', fav)));
    state.recents.forEach((rec) => {
      if (state.favorites.some((f) => isSameCoord(f.latitude, f.longitude, rec.latitude, rec.longitude))) return;
      el.chipsContainer.appendChild(makeChip('🕒', rec, 'recent'));
    });
  }

  function addRecent(place) {
    const entry = { name: place.name, address: place.address, latitude: place.latitude, longitude: place.longitude };
    state.recents = [entry].concat(
      state.recents.filter((r) => !isSameCoord(r.latitude, r.longitude, place.latitude, place.longitude))
    ).slice(0, 8);
  }

  // --- Actions ---
  async function toggleMock() {
    haptic(30);
    if (!state.isMocking) {
      state.isMocking = true;
      addRecent(state.selectedPlace);
      requestWakeLock();
      updateUI();
      const ok = await syncServer(true);
      showToast(ok
        ? `Mocking active: ${state.selectedPlace.name.split(',')[0]}`
        : 'Mocking active (offline — server not reachable)');
    } else {
      state.isMocking = false;
      releaseWakeLock();
      updateUI();
      fetch('/api/mock/stop', { method: 'POST' }).catch(() => {});
      showToast('Mock stopped. Real location restored.');
    }
  }

  function toggleFavorite() {
    haptic(20);
    const place = state.selectedPlace;
    const isFav = state.favorites.some((f) => isSameCoord(f.latitude, f.longitude, place.latitude, place.longitude));

    if (isFav) {
      state.favorites = state.favorites.filter((f) => !isSameCoord(f.latitude, f.longitude, place.latitude, place.longitude));
      fetch(`/api/favorites?lat=${place.latitude}&lon=${place.longitude}`, { method: 'DELETE' }).catch(() => {});
      showToast('Removed from favorites');
    } else {
      const fav = { name: place.name, address: place.address || '', latitude: place.latitude, longitude: place.longitude };
      state.favorites.push(fav);
      fetch('/api/favorites', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(fav),
      }).catch(() => {});
      showToast('Saved to favorites');
    }
    updateUI();
  }

  function selectCoordinates(lat, lng, defaultName) {
    selectPlace({
      name: defaultName || `${lat.toFixed(5)}, ${lng.toFixed(5)}`,
      address: `${lat.toFixed(6)}, ${lng.toFixed(6)}`,
      latitude: lat,
      longitude: lng,
    }, true);
  }

  function selectPlace(place, keepSearch) {
    haptic(15);
    state.selectedPlace = {
      name: place.name,
      address: place.address || '',
      latitude: Number(place.latitude),
      longitude: Number(place.longitude),
    };
    state.effectiveCoords = { latitude: state.selectedPlace.latitude, longitude: state.selectedPlace.longitude };
    if (!keepSearch) el.searchInput.value = place.name;
    hideSuggestions();
    if (state.isMocking) {
      addRecent(state.selectedPlace);
      syncServer(true);
    }
    updateUI();
  }

  // --- Search ---
  function hideSuggestions() {
    el.suggestionsCard.style.display = 'none';
  }

  function handleSearchInput(e) {
    const val = e.target.value.trim();
    el.searchClear.style.display = val ? 'block' : 'none';

    clearTimeout(searchTimer);
    if (val.length < 2) {
      hideSuggestions();
      el.searchSpinner.style.display = 'none';
      return;
    }

    el.searchSpinner.style.display = 'block';
    const seq = ++searchSeq;
    searchTimer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(val)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const suggestions = await res.json();
        if (seq === searchSeq) renderSuggestions(suggestions, val);
      } catch (err) {
        if (seq === searchSeq) renderSuggestions([], val, true);
      } finally {
        if (seq === searchSeq) el.searchSpinner.style.display = 'none';
      }
    }, 320);
  }

  function renderSuggestions(list, query, failed) {
    el.suggestionsCard.replaceChildren();
    if (!list || list.length === 0) {
      const empty = document.createElement('div');
      empty.className = 'suggestion-item';
      empty.style.cursor = 'default';
      empty.textContent = failed ? 'Search failed — check your connection' : `No results for “${query}”`;
      el.suggestionsCard.appendChild(empty);
      el.suggestionsCard.style.display = 'flex';
      return;
    }

    list.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'suggestion-item';
      const icon = document.createElement('div');
      icon.className = 'suggestion-icon';
      icon.textContent = item.type === 'coordinate' ? '📍' : '🏢';
      const info = document.createElement('div');
      info.className = 'suggestion-info';
      const name = document.createElement('span');
      name.className = 'suggestion-name';
      name.textContent = item.name;
      const addr = document.createElement('span');
      addr.className = 'suggestion-address';
      addr.textContent = item.address;
      info.append(name, addr);
      row.append(icon, info);
      row.onclick = () => selectPlace(item);
      el.suggestionsCard.appendChild(row);
    });

    el.suggestionsCard.style.display = 'flex';
  }

  function submitSearch() {
    // Enter key: jump to first suggestion, or parse "lat, lng" directly
    const val = el.searchInput.value.trim();
    const m = val.match(/^\s*(-?\d{1,2}(?:\.\d+)?)\s*[, ]\s*(-?\d{1,3}(?:\.\d+)?)\s*$/);
    if (m) {
      const lat = parseFloat(m[1]);
      const lng = parseFloat(m[2]);
      if (Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
        selectCoordinates(lat, lng, 'Pinned Coordinates');
        return;
      }
    }
    const first = el.suggestionsCard.querySelector('.suggestion-item');
    if (first && first.onclick) first.onclick();
  }

  // --- Movement (joystick / keyboard), computed locally ---
  function stepBy(bearing, distM) {
    const p = destination(state.selectedPlace.latitude, state.selectedPlace.longitude, bearing, distM);
    state.selectedPlace = {
      name: state.selectedPlace.name,
      address: `${p.latitude.toFixed(6)}, ${p.longitude.toFixed(6)}`,
      latitude: p.latitude,
      longitude: p.longitude,
    };
    state.effectiveCoords = p;
    el.placeCoords.textContent = `${p.latitude.toFixed(6)}, ${p.longitude.toFixed(6)}`;
    moveMarker(p.latitude, p.longitude, true);
    updateTelemetryUI();
    saveState();
    scheduleMoveSync();
  }

  function setupJoystick() {
    let isDragging = false;
    let baseRect = null;
    const maxRadius = 45;

    function point(e) {
      const t = e.touches && e.touches[0];
      return t ? { x: t.clientX, y: t.clientY } : { x: e.clientX, y: e.clientY };
    }

    function handleStart(e) {
      isDragging = true;
      baseRect = el.joystickBase.getBoundingClientRect();
      if (e.cancelable) e.preventDefault();
      handleMove(e);
      startJoystickInterval();
    }

    function handleMove(e) {
      if (!isDragging) return;
      if (e.cancelable && e.type === 'touchmove') e.preventDefault();
      const { x, y } = point(e);
      let dx = x - (baseRect.left + baseRect.width / 2);
      let dy = y - (baseRect.top + baseRect.height / 2);
      const distance = Math.hypot(dx, dy);
      if (distance > maxRadius) {
        dx = (dx / distance) * maxRadius;
        dy = (dy / distance) * maxRadius;
      }
      el.joystickNub.style.transform = `translate(${dx}px, ${dy}px)`;
      state.joystickVector = { x: dx / maxRadius, y: dy / maxRadius };
    }

    function handleEnd() {
      if (!isDragging) return;
      isDragging = false;
      el.joystickNub.style.transform = 'translate(0px, 0px)';
      state.joystickVector = { x: 0, y: 0 };
      stopJoystickInterval();
    }

    el.joystickBase.addEventListener('mousedown', handleStart);
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleEnd);
    el.joystickBase.addEventListener('touchstart', handleStart, { passive: false });
    window.addEventListener('touchmove', handleMove, { passive: false });
    window.addEventListener('touchend', handleEnd);
    window.addEventListener('touchcancel', handleEnd);

    document.querySelectorAll('.speed-chip').forEach((btn) => {
      btn.onclick = () => {
        state.simSpeedKmh = parseFloat(btn.dataset.speed);
        syncSettingsUI();
        updateTelemetryUI();
        saveState();
      };
    });
  }

  function startJoystickInterval() {
    if (state.joystickInterval) return;
    state.joystickInterval = setInterval(() => {
      const { x, y } = state.joystickVector;
      const mag = Math.min(1, Math.hypot(x, y));
      if (mag < 0.15) return;
      let angle = Math.atan2(x, -y); // 0 = north, screen y grows downward
      if (angle < 0) angle += 2 * Math.PI;
      const speedMs = state.simSpeedKmh / 3.6;
      // tick every 250ms; scale by stick deflection
      stepBy((angle * 180) / Math.PI, Math.max(0.5, speedMs * 0.25 * mag));
    }, 250);
  }

  function stopJoystickInterval() {
    clearInterval(state.joystickInterval);
    state.joystickInterval = null;
  }

  function onKeyDown(e) {
    const tag = (document.activeElement && document.activeElement.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA') return;
    const map = { ArrowUp: 0, w: 0, W: 0, ArrowRight: 90, d: 90, D: 90, ArrowDown: 180, s: 180, S: 180, ArrowLeft: 270, a: 270, A: 270 };
    if (!(e.key in map)) return;
    e.preventDefault();
    haptic(10);
    stepBy(map[e.key], Math.max(5.0, state.simSpeedKmh / 3.6));
  }

  // --- Clipboard with fallback (navigator.clipboard needs HTTPS) ---
  function copyText(text, okMsg) {
    const fallback = () => {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      let ok = false;
      try { ok = document.execCommand('copy'); } catch (e) { /* ignore */ }
      ta.remove();
      showToast(ok ? okMsg : 'Copy failed — select and copy manually');
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(() => showToast(okMsg), fallback);
    } else {
      fallback();
    }
  }

  function exportQuery() {
    const p = state.selectedPlace;
    return `lat=${p.latitude.toFixed(7)}&lon=${p.longitude.toFixed(7)}&name=${encodeURIComponent(p.name || '')}`;
  }

  // --- Event Listeners ---
  function setupEventListeners() {
    el.btnSetMock.onclick = toggleMock;
    el.btnFavorite.onclick = toggleFavorite;

    el.searchInput.addEventListener('input', handleSearchInput);
    el.searchInput.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') { e.preventDefault(); submitSearch(); }
      if (e.key === 'Escape') hideSuggestions();
    });
    el.searchClear.onclick = () => {
      el.searchInput.value = '';
      el.searchClear.style.display = 'none';
      hideSuggestions();
    };
    document.addEventListener('click', (e) => {
      if (!e.target.closest('.search-container')) hideSuggestions();
    });

    const copyCoords = () => {
      haptic(15);
      copyText(`${state.selectedPlace.latitude.toFixed(6)}, ${state.selectedPlace.longitude.toFixed(6)}`, 'Coordinates copied!');
    };
    el.btnCopyCoords.onclick = copyCoords;
    el.placeCoords.onclick = copyCoords;

    el.btnLocateMe.onclick = () => {
      haptic(20);
      if (!navigator.geolocation) {
        showToast('Geolocation not supported in this browser');
        return;
      }
      if (!window.isSecureContext) {
        showToast('Real GPS needs HTTPS — open via your Vercel URL');
        return;
      }
      showToast('Acquiring real GPS fix...');
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          selectCoordinates(pos.coords.latitude, pos.coords.longitude, 'My Current Location');
          showToast('Centered on current GPS');
        },
        (err) => showToast(err.code === 1 ? 'Location permission denied' : 'Could not get GPS fix'),
        { enableHighAccuracy: true, timeout: 10000 }
      );
    };

    el.btnToggleJoystick.onclick = () => {
      haptic(15);
      state.joystickOpen = !state.joystickOpen;
      el.joystickPanel.classList.toggle('visible', state.joystickOpen);
      el.btnToggleJoystick.classList.toggle('active', state.joystickOpen);
    };

    window.addEventListener('keydown', onKeyDown);

    el.btnOpenGuide.onclick = () => openModal(el.guideModal);
    el.btnOpenSettings.onclick = () => openModal(el.settingsModal);

    document.querySelectorAll('.modal-overlay').forEach((modal) => {
      modal.addEventListener('click', (e) => { if (e.target === modal) closeModal(modal); });
    });
    document.querySelectorAll('.btn-close-modal').forEach((btn) => {
      btn.addEventListener('click', () => closeModal(btn.closest('.modal-overlay')));
    });

    document.querySelectorAll('.modal-tab-btn').forEach((tab) => {
      tab.onclick = () => {
        document.querySelectorAll('.modal-tab-btn').forEach((t) => t.classList.remove('active'));
        document.querySelectorAll('.guide-tab-content').forEach((c) => { c.style.display = 'none'; });
        tab.classList.add('active');
        const target = $(tab.dataset.tab);
        if (target) target.style.display = 'flex';
      };
    });

    if (el.settingJitter) {
      el.settingJitter.onchange = (e) => {
        state.jitterEnabled = e.target.checked;
        if (!state.jitterEnabled) {
          state.effectiveCoords = { latitude: state.selectedPlace.latitude, longitude: state.selectedPlace.longitude };
          state.accuracy = 7.0;
          moveMarker(state.selectedPlace.latitude, state.selectedPlace.longitude, false);
          updateTelemetryUI();
        }
        saveState();
        syncServer(false);
      };
    }
    if (el.settingJitterMeters) {
      el.settingJitterMeters.oninput = (e) => {
        state.jitterMeters = parseFloat(e.target.value);
        el.settingJitterVal.textContent = `±${state.jitterMeters}m`;
        saveState();
      };
      el.settingJitterMeters.onchange = () => syncServer(false);
    }

    $('btn-download-gpx').onclick = () => { window.location.href = `/api/export/gpx?${exportQuery()}`; };
    $('btn-download-adb').onclick = () => { window.location.href = `/api/export/adb?${exportQuery()}`; };
    $('btn-copy-hook').onclick = () => {
      const hookUrl = `${window.location.origin}/api/hook.js?${exportQuery()}`;
      const code = `javascript:(function(){var s=document.createElement('script');s.src='${hookUrl}';document.body.appendChild(s);})();`;
      copyText(code, 'Bookmarklet copied! Save it as a bookmark URL.');
    };
  }

  function openModal(m) { m.classList.add('open'); }
  function closeModal(m) { if (m) m.classList.remove('open'); }

  // --- Screen Wake Lock ---
  async function requestWakeLock() {
    try {
      if ('wakeLock' in navigator && !state.wakeLock) {
        state.wakeLock = await navigator.wakeLock.request('screen');
        state.wakeLock.addEventListener('release', () => { state.wakeLock = null; });
      }
    } catch (e) { /* not allowed (e.g. page hidden) */ }
  }

  function releaseWakeLock() {
    if (state.wakeLock) {
      state.wakeLock.release().catch(() => {});
      state.wakeLock = null;
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && state.isMocking) requestWakeLock();
  });

  function haptic(ms) {
    try { if (navigator.vibrate) navigator.vibrate(ms); } catch (e) { /* ignore */ }
  }

  let toastTimer = null;
  function showToast(msg) {
    clearTimeout(toastTimer);
    el.toast.textContent = msg;
    el.toast.classList.add('show');
    toastTimer = setTimeout(() => el.toast.classList.remove('show'), 2600);
  }

  function isSameCoord(lat1, lon1, lat2, lon2) {
    return Math.abs(lat1 - lat2) < 0.0001 && Math.abs(lon1 - lon2) < 0.0001;
  }

  // --- PWA ---
  function setupPwa() {
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch((err) => console.warn('[PWA] SW registration failed:', err));
    }

    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      state.deferredPrompt = e;
      el.btnInstallPwa.style.display = 'grid';
    });

    const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent);
    const standalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone;
    if (isIOS && !standalone) el.btnInstallPwa.style.display = 'grid';

    el.btnInstallPwa.onclick = async () => {
      if (state.deferredPrompt) {
        state.deferredPrompt.prompt();
        const choice = await state.deferredPrompt.userChoice;
        if (choice.outcome === 'accepted') showToast('Jelly Loc added to Home Screen!');
        state.deferredPrompt = null;
        el.btnInstallPwa.style.display = 'none';
      } else if (isIOS) {
        showToast('On iOS: tap Share ⎙ → Add to Home Screen');
      } else {
        showToast('Already installed, or use browser menu → Install app');
      }
    };
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
