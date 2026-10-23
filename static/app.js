/**
 * Jelly Loc — Mobile PWA & GPS Emulation Engine
 * Interactive Leaflet Map, Nominatim Search, Virtual Joystick, Route Simulator,
 * Wake Lock API, Haptic Feedback, and Mobile Device Integrations.
 */

(() => {
  'use strict';

  // Application State
  const state = {
    isMocking: false,
    selectedPlace: {
      name: 'Charminar, Hyderabad',
      address: 'Hyderabad, Telangana, India',
      latitude: 17.361563,
      longitude: 78.474665,
    },
    effectiveCoords: {
      latitude: 17.361563,
      longitude: 78.474665,
    },
    accuracy: 7.0,
    altitude: 505.0,
    speed: 0.0,
    bearing: 0.0,
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

  // DOM Elements
  const el = {
    map: document.getElementById('map'),
    statusPill: document.getElementById('status-pill'),
    statusText: document.getElementById('status-text'),
    searchInput: document.getElementById('search-input'),
    searchSpinner: document.getElementById('search-spinner'),
    searchClear: document.getElementById('search-clear'),
    suggestionsCard: document.getElementById('suggestions-card'),
    placeTitle: document.getElementById('place-title'),
    placeCoords: document.getElementById('place-coords'),
    btnCopyCoords: document.getElementById('btn-copy-coords'),
    btnFavorite: document.getElementById('btn-favorite'),
    favoriteIcon: document.getElementById('favorite-icon'),
    telemetryLat: document.getElementById('telemetry-lat'),
    telemetryLon: document.getElementById('telemetry-lon'),
    telemetryAcc: document.getElementById('telemetry-acc'),
    telemetrySpeed: document.getElementById('telemetry-speed'),
    btnSetMock: document.getElementById('btn-set-mock'),
    btnSetMockText: document.getElementById('btn-set-mock-text'),
    chipsContainer: document.getElementById('chips-container'),
    btnLocateMe: document.getElementById('btn-locate-me'),
    btnToggleJoystick: document.getElementById('btn-toggle-joystick'),
    btnOpenGuide: document.getElementById('btn-open-guide'),
    btnOpenSettings: document.getElementById('btn-open-settings'),
    btnInstallPwa: document.getElementById('btn-install-pwa'),
    joystickPanel: document.getElementById('joystick-panel'),
    joystickBase: document.getElementById('joystick-base'),
    joystickNub: document.getElementById('joystick-nub'),
    guideModal: document.getElementById('guide-modal'),
    settingsModal: document.getElementById('settings-modal'),
    toast: document.getElementById('toast'),
    settingJitter: document.getElementById('setting-jitter'),
    settingJitterMeters: document.getElementById('setting-jitter-meters'),
    settingJitterVal: document.getElementById('setting-jitter-val'),
  };

  let mapInstance = null;
  let mockMarker = null;
  let accuracyCircle = null;
  let searchTimer = null;
  let telemetryPollTimer = null;

  // --- Initial Setup ---
  async function init() {
    initLeafletMap();
    setupEventListeners();
    setupJoystick();
    setupPwa();
    await fetchInitialState();
    startTelemetryPolling();
  }

  // --- Leaflet Map Initialization ---
  function initLeafletMap() {
    const startLat = state.selectedPlace.latitude;
    const startLng = state.selectedPlace.longitude;

    // Create Map
    mapInstance = L.map('map', {
      center: [startLat, startLng],
      zoom: 15,
      zoomControl: false,
      attributionControl: false,
    });

    // Dark Matter CartoDB Basemap Tiles
    L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/dark_all/{z}/{x}/{y}{r}.png', {
      maxZoom: 19,
      subdomains: 'abcd',
    }).addTo(mapInstance);

    // Custom Glowing Neon Pulsing Marker
    const customIcon = L.divIcon({
      className: 'jelly-marker',
      html: `
        <div class="jelly-marker-pulse"></div>
        <div class="jelly-marker-pin"></div>
      `,
      iconSize: [44, 44],
      iconAnchor: [22, 42],
    });

    mockMarker = L.marker([startLat, startLng], {
      icon: customIcon,
      draggable: true,
    }).addTo(mapInstance);

    // Accuracy Circle
    accuracyCircle = L.circle([startLat, startLng], {
      radius: state.accuracy,
      color: '#00f5d4',
      weight: 1,
      fillColor: '#00f5d4',
      fillOpacity: 0.12,
    }).addTo(mapInstance);

    // Drag marker event
    mockMarker.on('dragend', async (e) => {
      const pos = e.target.getLatLng();
      haptic(15);
      await selectCoordinates(pos.lat, pos.lng, 'Pinned location');
    });

    // Single tap / click on map to move target
    mapInstance.on('click', async (e) => {
      haptic(15);
      await selectCoordinates(e.latlng.lat, e.latlng.lng, 'Dropped pin');
    });
  }

  // --- State Synchronization ---
  async function fetchInitialState() {
    try {
      const [statusRes, favsRes, recentsRes] = await Promise.all([
        fetch('/api/status').then(r => r.json()),
        fetch('/api/favorites').then(r => r.json()),
        fetch('/api/recents').then(r => r.json()),
      ]);

      if (statusRes) {
        state.isMocking = statusRes.is_mocking;
        state.selectedPlace = {
          name: statusRes.name,
          address: statusRes.address,
          latitude: statusRes.base_latitude || statusRes.latitude,
          longitude: statusRes.base_longitude || statusRes.longitude,
        };
        state.effectiveCoords = {
          latitude: statusRes.latitude,
          longitude: statusRes.longitude,
        };
        state.accuracy = statusRes.accuracy || 7.0;
        state.altitude = statusRes.altitude || 500;
        state.jitterEnabled = statusRes.jitter_enabled;
        state.jitterMeters = statusRes.jitter_meters;
        state.simSpeedKmh = statusRes.sim_speed_kmh || 5.0;
      }

      if (Array.isArray(favsRes)) state.favorites = favsRes;
      if (Array.isArray(recentsRes)) state.recents = recentsRes;

      updateUI();
    } catch (e) {
      console.warn('Initial fetch error:', e);
    }
  }

  function startTelemetryPolling() {
    clearInterval(telemetryPollTimer);
    telemetryPollTimer = setInterval(async () => {
      if (!state.isMocking && !state.joystickOpen) return;
      try {
        const res = await fetch('/api/status');
        const data = await res.json();
        if (data) {
          state.effectiveCoords.latitude = data.latitude;
          state.effectiveCoords.longitude = data.longitude;
          state.accuracy = data.accuracy;
          state.speed = data.speed;
          state.bearing = data.bearing;
          updateTelemetryUI();

          // Smoothly update marker position and accuracy radius
          if (mockMarker) {
            mockMarker.setLatLng([data.latitude, data.longitude]);
          }
          if (accuracyCircle) {
            accuracyCircle.setLatLng([data.latitude, data.longitude]);
            accuracyCircle.setRadius(data.accuracy);
          }
        }
      } catch (err) {
        // Silent catch
      }
    }, 1000);
  }

  // --- UI Update Helpers ---
  function updateUI() {
    const lat = state.selectedPlace.latitude;
    const lng = state.selectedPlace.longitude;

    // Place group
    el.placeTitle.textContent = state.selectedPlace.name || 'Selected Location';
    el.placeCoords.textContent = `${lat.toFixed(6)}, ${lng.toFixed(6)}`;

    // Marker & Map
    if (mockMarker) mockMarker.setLatLng([lat, lng]);
    if (accuracyCircle) {
      accuracyCircle.setLatLng([lat, lng]);
      accuracyCircle.setRadius(state.accuracy);
    }
    if (mapInstance) {
      mapInstance.panTo([lat, lng], { animate: true, duration: 0.6 });
    }

    // Status Pill
    if (state.isMocking) {
      el.statusPill.classList.add('active');
      el.statusText.textContent = `Mocking: ${state.selectedPlace.name.split(',')[0]}`;
      el.btnSetMock.classList.add('mocking');
      el.btnSetMockText.textContent = 'Stop Mocking';
    } else {
      el.statusPill.classList.remove('active');
      el.statusText.textContent = 'Idle';
      el.btnSetMock.classList.remove('mocking');
      el.btnSetMockText.textContent = 'Set Location';
    }

    // Favorite button state
    const isFav = state.favorites.some(f => isSameCoord(f.latitude, f.longitude, lat, lng));
    if (isFav) {
      el.btnFavorite.classList.add('active');
      el.favoriteIcon.textContent = '♥';
    } else {
      el.btnFavorite.classList.remove('active');
      el.favoriteIcon.textContent = '♡';
    }

    updateTelemetryUI();
    renderChips();
  }

  function updateTelemetryUI() {
    const lat = state.effectiveCoords.latitude;
    const lon = state.effectiveCoords.longitude;
    el.telemetryLat.textContent = lat.toFixed(5);
    el.telemetryLon.textContent = lon.toFixed(5);
    el.telemetryAcc.textContent = `±${state.accuracy.toFixed(1)}m`;
    el.telemetrySpeed.textContent = `${state.simSpeedKmh.toFixed(0)} km/h`;
  }

  function renderChips() {
    el.chipsContainer.innerHTML = '';

    // Favorites
    state.favorites.forEach((fav) => {
      const chip = document.createElement('div');
      chip.className = 'quick-chip';
      chip.innerHTML = `<span>★</span> <span>${fav.name.split(',')[0]}</span>`;
      chip.onclick = () => selectPlace(fav);
      el.chipsContainer.appendChild(chip);
    });

    // Recents
    state.recents.forEach((rec) => {
      if (state.favorites.some(f => isSameCoord(f.latitude, f.longitude, rec.latitude, rec.longitude))) {
        return;
      }
      const chip = document.createElement('div');
      chip.className = 'quick-chip recent';
      chip.innerHTML = `<span>🕒</span> <span>${rec.name.split(',')[0]}</span>`;
      chip.onclick = () => selectPlace(rec);
      el.chipsContainer.appendChild(chip);
    });
  }

  // --- Actions ---
  async function toggleMock() {
    haptic(30);
    if (!state.isMocking) {
      // Start Mocking
      try {
        const res = await fetch('/api/mock/set', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            latitude: state.selectedPlace.latitude,
            longitude: state.selectedPlace.longitude,
            name: state.selectedPlace.name,
            address: state.selectedPlace.address,
            jitter: state.jitterEnabled,
            speed_kmh: state.simSpeedKmh,
            altitude: state.altitude,
          }),
        });
        const data = await res.json();
        state.isMocking = true;
        requestWakeLock();
        showToast(`Mocking active: ${state.selectedPlace.name.split(',')[0]}`);
      } catch (err) {
        showToast('Failed to start mock location');
      }
    } else {
      // Stop Mocking
      try {
        await fetch('/api/mock/stop', { method: 'POST' });
        state.isMocking = false;
        releaseWakeLock();
        showToast('Mock stopped. Real location restored.');
      } catch (err) {
        showToast('Error stopping mock');
      }
    }
    updateUI();
  }

  async function toggleFavorite() {
    haptic(20);
    const place = state.selectedPlace;
    const isFav = state.favorites.some(f => isSameCoord(f.latitude, f.longitude, place.latitude, place.longitude));

    if (isFav) {
      await fetch(`/api/favorites?lat=${place.latitude}&lon=${place.longitude}`, { method: 'DELETE' });
      state.favorites = state.favorites.filter(f => !isSameCoord(f.latitude, f.longitude, place.latitude, place.longitude));
      showToast('Removed from favorites');
    } else {
      const newFav = {
        name: place.name,
        address: place.address,
        latitude: place.latitude,
        longitude: place.longitude,
      };
      await fetch('/api/favorites', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(newFav),
      });
      state.favorites.push(newFav);
      showToast('Saved to favorites');
    }
    updateUI();
  }

  async function selectCoordinates(lat, lng, defaultName) {
    state.selectedPlace = {
      name: defaultName || `${lat.toFixed(5)}, ${lng.toFixed(5)}`,
      address: `${lat.toFixed(6)}, ${lng.toFixed(6)}`,
      latitude: lat,
      longitude: lng,
    };
    state.effectiveCoords = { latitude: lat, longitude: lng };

    if (state.isMocking) {
      await fetch('/api/mock/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          latitude: lat,
          longitude: lng,
          name: state.selectedPlace.name,
          address: state.selectedPlace.address,
        }),
      });
    }
    updateUI();
  }

  async function selectPlace(place) {
    haptic(15);
    state.selectedPlace = place;
    state.effectiveCoords = { latitude: place.latitude, longitude: place.longitude };
    el.searchInput.value = place.name;
    el.suggestionsCard.style.display = 'none';

    if (state.isMocking) {
      await fetch('/api/mock/set', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          latitude: place.latitude,
          longitude: place.longitude,
          name: place.name,
          address: place.address,
        }),
      });
    }
    updateUI();
  }

  // --- Nominatim Search ---
  function handleSearchInput(e) {
    const val = e.target.value.trim();
    el.searchClear.style.display = val ? 'block' : 'none';

    clearTimeout(searchTimer);
    if (val.length < 2) {
      el.suggestionsCard.style.display = 'none';
      el.searchSpinner.style.display = 'none';
      return;
    }

    el.searchSpinner.style.display = 'block';
    searchTimer = setTimeout(async () => {
      try {
        const res = await fetch(`/api/search?q=${encodeURIComponent(val)}`);
        const suggestions = await res.json();
        renderSuggestions(suggestions);
      } catch (err) {
        console.error('Search error:', err);
      } finally {
        el.searchSpinner.style.display = 'none';
      }
    }, 320);
  }

  function renderSuggestions(list) {
    el.suggestionsCard.innerHTML = '';
    if (!list || list.length === 0) {
      el.suggestionsCard.style.display = 'none';
      return;
    }

    list.forEach((item) => {
      const row = document.createElement('div');
      row.className = 'suggestion-item';
      row.innerHTML = `
        <div class="suggestion-icon">${item.type === 'coordinate' ? '📍' : '🏢'}</div>
        <div class="suggestion-info">
          <span class="suggestion-name">${item.name}</span>
          <span class="suggestion-address">${item.address}</span>
        </div>
      `;
      row.onclick = () => selectPlace(item);
      el.suggestionsCard.appendChild(row);
    });

    el.suggestionsCard.style.display = 'flex';
  }

  // --- Virtual Joystick / D-Pad Logic ---
  function setupJoystick() {
    let isDragging = false;
    let baseRect = null;
    const maxRadius = 45;

    function handleStart(e) {
      isDragging = true;
      baseRect = el.joystickBase.getBoundingClientRect();
      handleMove(e);
      startJoystickInterval();
    }

    function handleMove(e) {
      if (!isDragging) return;
      const clientX = e.touches ? e.touches[0].clientX : e.clientX;
      const clientY = e.touches ? e.touches[0].clientY : e.clientY;

      const centerX = baseRect.left + baseRect.width / 2;
      const centerY = baseRect.top + baseRect.height / 2;

      let dx = clientX - centerX;
      let dy = clientY - centerY;
      const distance = Math.sqrt(dx * dx + dy * dy);

      if (distance > maxRadius) {
        dx = (dx / distance) * maxRadius;
        dy = (dy / distance) * maxRadius;
      }

      el.joystickNub.style.transform = `translate(${dx}px, ${dy}px)`;
      state.joystickVector = {
        x: dx / maxRadius,
        y: dy / maxRadius,
      };
    }

    function handleEnd() {
      if (!isDragging) return;
      isDragging = false;
      el.joystickNub.style.transform = `translate(0px, 0px)`;
      state.joystickVector = { x: 0, y: 0 };
      stopJoystickInterval();
    }

    el.joystickBase.addEventListener('mousedown', handleStart);
    window.addEventListener('mousemove', handleMove);
    window.addEventListener('mouseup', handleEnd);

    el.joystickBase.addEventListener('touchstart', handleStart, { passive: false });
    window.addEventListener('touchmove', handleMove, { passive: false });
    window.addEventListener('touchend', handleEnd);

    // Speed chips
    document.querySelectorAll('.speed-chip').forEach((btn) => {
      btn.onclick = () => {
        document.querySelectorAll('.speed-chip').forEach(b => b.classList.remove('selected'));
        btn.classList.add('selected');
        state.simSpeedKmh = parseFloat(btn.dataset.speed);
        updateTelemetryUI();
      };
    });
  }

  function startJoystickInterval() {
    if (state.joystickInterval) return;
    state.joystickInterval = setInterval(async () => {
      const { x, y } = state.joystickVector;
      if (Math.abs(x) < 0.15 && Math.abs(y) < 0.15) return;

      // Calculate bearing angle (0 = North, 90 = East, 180 = South, 270 = West)
      // Note: y is positive downwards on screen
      let angleRad = Math.atan2(x, -y);
      if (angleRad < 0) angleRad += 2 * Math.PI;
      const bearing = (angleRad * 180) / Math.PI;

      // Distance step based on speed: (speed km/h) / 3.6 = m/s, step runs every 500ms
      const speedMs = state.simSpeedKmh / 3.6;
      const stepDistance = Math.max(2.0, speedMs * 0.5);

      try {
        const res = await fetch('/api/mock/step', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            bearing: Math.round(bearing),
            distance_meters: stepDistance,
          }),
        });
        const data = await res.json();
        if (data && data.location) {
          state.selectedPlace.latitude = data.location.base_latitude;
          state.selectedPlace.longitude = data.location.base_longitude;
          state.effectiveCoords.latitude = data.location.latitude;
          state.effectiveCoords.longitude = data.location.longitude;
          state.accuracy = data.location.accuracy;

          if (mockMarker) mockMarker.setLatLng([data.location.latitude, data.location.longitude]);
          if (accuracyCircle) {
            accuracyCircle.setLatLng([data.location.latitude, data.location.longitude]);
            accuracyCircle.setRadius(data.location.accuracy);
          }
          if (mapInstance) mapInstance.panTo([data.location.latitude, data.location.longitude]);
          updateTelemetryUI();
        }
      } catch (err) {
        // Step failed
      }
    }, 500);
  }

  function stopJoystickInterval() {
    clearInterval(state.joystickInterval);
    state.joystickInterval = null;
  }

  // Keyboard WASD / Arrow Keys Support
  window.addEventListener('keydown', (e) => {
    if (document.activeElement === el.searchInput) return;
    let bearing = null;
    if (e.key === 'ArrowUp' || e.key === 'w' || e.key === 'W') bearing = 0;
    else if (e.key === 'ArrowRight' || e.key === 'd' || e.key === 'D') bearing = 90;
    else if (e.key === 'ArrowDown' || e.key === 's' || e.key === 'S') bearing = 180;
    else if (e.key === 'ArrowLeft' || e.key === 'a' || e.key === 'A') bearing = 270;

    if (bearing !== null) {
      haptic(10);
      const speedMs = state.simSpeedKmh / 3.6;
      fetch('/api/mock/step', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bearing, distance_meters: Math.max(5.0, speedMs * 1.0) }),
      }).then(r => r.json()).then(data => {
        if (data && data.location) {
          state.selectedPlace.latitude = data.location.base_latitude;
          state.selectedPlace.longitude = data.location.base_longitude;
          mockMarker.setLatLng([data.location.latitude, data.location.longitude]);
          mapInstance.panTo([data.location.latitude, data.location.longitude]);
          updateTelemetryUI();
        }
      });
    }
  });

  // --- Event Listeners Setup ---
  function setupEventListeners() {
    // Primary Actions
    el.btnSetMock.onclick = toggleMock;
    el.btnFavorite.onclick = toggleFavorite;

    // Search events
    el.searchInput.addEventListener('input', handleSearchInput);
    el.searchClear.onclick = () => {
      el.searchInput.value = '';
      el.searchClear.style.display = 'none';
      el.suggestionsCard.style.display = 'none';
    };

    // Copy coords
    el.btnCopyCoords.onclick = () => {
      const text = `${state.selectedPlace.latitude.toFixed(6)}, ${state.selectedPlace.longitude.toFixed(6)}`;
      navigator.clipboard.writeText(text);
      haptic(15);
      showToast('Coordinates copied!');
    };

    // Locate Me
    el.btnLocateMe.onclick = () => {
      haptic(20);
      if (navigator.geolocation) {
        showToast('Acquiring real GPS fix...');
        navigator.geolocation.getCurrentPosition(
          (pos) => {
            selectCoordinates(pos.coords.latitude, pos.coords.longitude, 'My Current Location');
            showToast('Centered on current GPS');
          },
          (err) => {
            showToast('Could not get GPS fix');
          },
          { enableHighAccuracy: true, timeout: 8000 }
        );
      }
    };

    // Toggle Joystick
    el.btnToggleJoystick.onclick = () => {
      haptic(15);
      state.joystickOpen = !state.joystickOpen;
      el.joystickPanel.classList.toggle('visible', state.joystickOpen);
      el.btnToggleJoystick.classList.toggle('active', state.joystickOpen);
    };

    // Modals
    el.btnOpenGuide.onclick = () => openModal(el.guideModal);
    el.btnOpenSettings.onclick = () => openModal(el.settingsModal);

    document.querySelectorAll('.modal-overlay').forEach((modal) => {
      modal.onclick = (e) => {
        if (e.target === modal) closeModal(modal);
      };
    });
    document.querySelectorAll('.btn-close-modal').forEach((btn) => {
      btn.onclick = () => {
        closeModal(btn.closest('.modal-overlay'));
      };
    });

    // Guide Modal Tabs
    document.querySelectorAll('.modal-tab-btn').forEach((tab) => {
      tab.onclick = () => {
        document.querySelectorAll('.modal-tab-btn').forEach(t => t.classList.remove('active'));
        document.querySelectorAll('.guide-tab-content').forEach(c => c.style.display = 'none');
        tab.classList.add('active');
        const targetId = tab.dataset.tab;
        const targetEl = document.getElementById(targetId);
        if (targetEl) targetEl.style.display = 'flex';
      };
    });

    // Settings Jitter slider
    if (el.settingJitter) {
      el.settingJitter.onchange = (e) => {
        state.jitterEnabled = e.target.checked;
        fetch('/api/mock/jitter', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: state.jitterEnabled }),
        });
      };
    }
    if (el.settingJitterMeters) {
      el.settingJitterMeters.oninput = (e) => {
        const val = parseFloat(e.target.value);
        el.settingJitterVal.textContent = `±${val}m`;
        state.jitterMeters = val;
        fetch('/api/mock/jitter', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ enabled: state.jitterEnabled, meters: val }),
        });
      };
    }

    // Export buttons
    document.getElementById('btn-download-gpx').onclick = () => {
      window.location.href = `/api/export/gpx?name=${encodeURIComponent(state.selectedPlace.name)}`;
    };
    document.getElementById('btn-download-adb').onclick = () => {
      window.location.href = '/api/export/adb';
    };
    document.getElementById('btn-copy-hook').onclick = () => {
      const hookUrl = `${window.location.origin}/api/hook.js`;
      const code = `javascript:(function(){var s=document.createElement('script');s.src='${hookUrl}';document.body.appendChild(s);})();`;
      navigator.clipboard.writeText(code);
      showToast('Bookmarklet copied! Paste in mobile browser.');
    };
  }

  // --- Modal Helpers ---
  function openModal(m) {
    m.classList.add('open');
  }

  function closeModal(m) {
    m.classList.remove('open');
  }

  // --- Screen Wake Lock API ---
  async function requestWakeLock() {
    try {
      if ('wakeLock' in navigator) {
        state.wakeLock = await navigator.wakeLock.request('screen');
      }
    } catch (e) {
      // Wake lock request failed
    }
  }

  function releaseWakeLock() {
    if (state.wakeLock) {
      state.wakeLock.release().then(() => {
        state.wakeLock = null;
      });
    }
  }

  // --- Haptic Feedback Helper ---
  function haptic(ms) {
    if (navigator.vibrate) {
      navigator.vibrate(ms);
    }
  }

  // --- Toast Notification Helper ---
  let toastTimer = null;
  function showToast(msg) {
    clearTimeout(toastTimer);
    el.toast.textContent = msg;
    el.toast.classList.add('show');
    toastTimer = setTimeout(() => {
      el.toast.classList.remove('show');
    }, 2400);
  }

  function isSameCoord(lat1, lon1, lat2, lon2) {
    return Math.abs(lat1 - lat2) < 0.0001 && Math.abs(lon1 - lon2) < 0.0001;
  }

  // --- PWA Installation & Service Worker Setup ---
  function setupPwa() {
    // Service Worker Registration
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').then((reg) => {
        console.log('[PWA] Service Worker registered:', reg.scope);
      }).catch((err) => {
        console.warn('[PWA] Service Worker registration failed:', err);
      });
    }

    // Android / Chrome Install Prompt
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      state.deferredPrompt = e;
      el.btnInstallPwa.style.display = 'grid';
    });

    el.btnInstallPwa.onclick = async () => {
      if (state.deferredPrompt) {
        state.deferredPrompt.prompt();
        const choice = await state.deferredPrompt.userChoice;
        if (choice.outcome === 'accepted') {
          showToast('Jelly Loc added to Home Screen!');
        }
        state.deferredPrompt = null;
        el.btnInstallPwa.style.display = 'none';
      } else {
        // Detect iOS
        const isIOS = /iPad|iPhone|iPod/.test(navigator.userAgent) && !window.MSStream;
        if (isIOS) {
          showToast('On iOS: Tap Share ⎙ → Add to Home Screen ⊞');
        } else {
          showToast('PWA already installed or standalone');
        }
      }
    };
  }

  // Start the application
  window.addEventListener('DOMContentLoaded', init);
})();
