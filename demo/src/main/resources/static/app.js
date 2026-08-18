(() => {
  "use strict";

  const $ = (id) => document.getElementById(id);
  const state = {
    currentFolder: folderFromLocation(),
    items: [],
    breadcrumbs: [],
    selected: new Set(),
    modal: null,
    authTimer: null,
    lastQrLink: null,
    driveVisible: false,
    view: localStorage.getItem("teledrive-view") || "grid",
    searchTimer: null
  };

  const el = {
    authView: $("auth-view"), driveView: $("drive-view"), authError: $("auth-error"), authStatus: $("auth-status"),
    credentialsForm: $("credentials-form"), credentialsToggle: $("credentials-toggle"), credentialsFields: $("credentials-fields"),
    apiId: $("api-id"), apiHash: $("api-hash"), qrTab: $("qr-tab"), phoneTab: $("phone-tab"),
    qrPanel: $("qr-panel"), phonePanel: $("phone-panel"), qrCode: $("qr-code"), qrExpiry: $("qr-expiry"),
    refreshQr: $("refresh-qr"), qrFallback: $("qr-link-fallback"), phoneForm: $("phone-form"), phoneNumber: $("phone-number"),
    codeForm: $("code-form"), verificationCode: $("verification-code"), passwordForm: $("password-form"), twoFactorPassword: $("two-factor-password"),
    globalSearch: $("global-search"), clearSearch: $("clear-search"), searchResults: $("search-results"), topbarSearchWrap: document.querySelector(".topbar-search-wrap"),
    accountInitials: $("account-initials"), logout: $("logout-button"), sync: $("sync-drive"), sidebar: $("sidebar"), sidebarToggle: $("sidebar-toggle"),
    newMenuButton: $("new-menu-button"), newMenu: $("new-menu"), myDrive: $("my-drive-nav"), storageRefresh: $("storage-refresh"),
    storageFill: $("storage-fill"), storageSummary: $("storage-summary"), driveError: $("drive-error"), breadcrumbs: $("breadcrumbs"),
    folderTitle: $("folder-title"), gridButton: $("grid-view-button"), listButton: $("list-view-button"), toolbarFolder: $("toolbar-folder-button"),
    toolbarUpload: $("toolbar-upload-button"), selectionToolbar: $("selection-toolbar"), selectionCount: $("selection-count"),
    driveLoading: $("drive-loading"), itemsGrid: $("items-grid"), emptyState: $("empty-state"), fileInput: $("file-upload-input"), directoryInput: $("directory-upload-input"),
    contextMenu: $("context-menu"), modalBackdrop: $("modal-backdrop"), modal: $("action-modal"), modalTitle: $("modal-title"),
    modalDescription: $("modal-description"), modalForm: $("modal-form"), modalLabel: $("modal-label"), modalInput: $("modal-input"),
    targetWrap: $("target-folder-wrap"), targetSelect: $("target-folder-select"), modalError: $("modal-error"), modalSubmit: $("modal-submit"),
    modalClose: $("modal-close"), modalCancel: $("modal-cancel"), uploadProgress: $("upload-progress"), uploadTitle: $("upload-title"),
    uploadPercent: $("upload-percent"), uploadFill: $("upload-progress-fill"), uploadDescription: $("upload-description"), toastRegion: $("toast-region")
  };

  function folderFromLocation() {
    return new URLSearchParams(window.location.search).get("folder") || "root";
  }

  function homeUrl(folderId = state.currentFolder) {
    return folderId && folderId !== "root" ? `/home?folder=${encodeURIComponent(folderId)}` : "/home";
  }

  function resetUnauthenticatedUi() {
    state.modal = null;
    state.selected.clear();
    document.body.classList.add("auth-page");
    el.modalBackdrop.hidden = true;
    el.contextMenu.hidden = true;
    el.newMenu.hidden = true;
    el.uploadProgress.hidden = true;
    el.searchResults.hidden = true;
    el.clearSearch.hidden = true;
    el.globalSearch.value = "";
    el.sidebar.classList.remove("open");
  }

  async function request(url, options = {}) {
    const settings = { credentials: "same-origin", ...options };
    settings.headers = { ...(options.headers || {}) };
    if (options.body && !(options.body instanceof FormData) && !settings.headers["Content-Type"]) {
      settings.headers["Content-Type"] = "application/json";
    }
    const response = await fetch(url, settings);
    const isJson = (response.headers.get("content-type") || "").includes("application/json");
    const body = isJson ? await response.json().catch(() => ({})) : await response.text().catch(() => "");
    if (!response.ok) {
      throw new Error(body?.message || body || "The request could not be completed.");
    }
    return body;
  }

  function json(method, body) {
    return { method, body: JSON.stringify(body) };
  }

  function displayError(target, message) {
    target.textContent = message;
    target.hidden = !message;
  }

  function clearError(target) {
    displayError(target, "");
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>'"]/g, (character) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[character]);
  }

  function toast(message, kind = "") {
    const node = document.createElement("div");
    node.className = `toast ${kind}`;
    node.textContent = message;
    el.toastRegion.append(node);
    window.setTimeout(() => node.remove(), 4000);
  }

  function showAuthStatus(message) {
    displayError(el.authStatus, message);
  }

  function credentials() {
    const apiId = Number.parseInt(el.apiId.value.trim(), 10);
    const apiHash = el.apiHash.value.trim();
    if (!Number.isInteger(apiId) || apiId <= 0 || !apiHash) {
      revealCredentials();
      throw new Error("Enter your Telegram API ID and API hash first.");
    }
    return { apiId, apiHash };
  }

  function revealCredentials() {
    el.credentialsFields.hidden = false;
    el.credentialsToggle.setAttribute("aria-expanded", "true");
  }

  function toggleCredentials() {
    const open = el.credentialsFields.hidden;
    el.credentialsFields.hidden = !open;
    el.credentialsToggle.setAttribute("aria-expanded", String(open));
  }

  function switchAuthTab(tab) {
    const qr = tab === "qr";
    el.qrTab.classList.toggle("active", qr);
    el.phoneTab.classList.toggle("active", !qr);
    el.qrTab.setAttribute("aria-selected", String(qr));
    el.phoneTab.setAttribute("aria-selected", String(!qr));
    el.qrPanel.hidden = !qr;
    el.qrPanel.classList.toggle("active", qr);
    el.phonePanel.hidden = qr;
    el.phonePanel.classList.toggle("active", !qr);
    if (!qr) showPhoneForm("phone");
  }

  function showPhoneForm(which) {
    el.phoneForm.hidden = which !== "phone";
    el.codeForm.hidden = which !== "code";
    el.passwordForm.hidden = which !== "password";
  }

  async function startQrLogin() {
    clearError(el.authError);
    try {
      const snapshot = await request("/api/auth/qr", json("POST", credentials()));
      renderAuth(snapshot);
      beginAuthPolling();
    } catch (error) {
      displayError(el.authError, error.message);
      el.qrExpiry.textContent = "Add your API settings, then refresh the QR code.";
    }
  }

  async function startPhoneLogin(event) {
    event.preventDefault();
    clearError(el.authError);
    try {
      const data = credentials();
      const number = el.phoneNumber.value.trim();
      data.phone = number.startsWith("+") ? number : `+${number}`;
      const snapshot = await request("/api/auth/phone", json("POST", data));
      showAuthStatus("Requesting a Telegram verification code…");
      renderAuth(snapshot);
      beginAuthPolling();
    } catch (error) {
      displayError(el.authError, error.message);
    }
  }

  async function submitCode(event) {
    event.preventDefault();
    try {
      const snapshot = await request("/api/auth/code", json("POST", { value: el.verificationCode.value }));
      showAuthStatus("Checking your code…");
      renderAuth(snapshot);
      beginAuthPolling();
    } catch (error) {
      displayError(el.authError, error.message);
    }
  }

  async function submitPassword(event) {
    event.preventDefault();
    try {
      const snapshot = await request("/api/auth/password", json("POST", { value: el.twoFactorPassword.value }));
      showAuthStatus("Unlocking your Telegram drive…");
      renderAuth(snapshot);
      beginAuthPolling();
    } catch (error) {
      displayError(el.authError, error.message);
    }
  }

  function beginAuthPolling() {
    if (state.authTimer) return;
    state.authTimer = window.setInterval(async () => {
      try { renderAuth(await request("/api/auth/state")); } catch { /* a transient poll failure is non-fatal */ }
    }, 1400);
  }

  function stopAuthPolling() {
    if (state.authTimer) window.clearInterval(state.authTimer);
    state.authTimer = null;
  }

  async function renderAuth(snapshot) {
    if (!snapshot || snapshot.step === "IDLE") return;
    if (snapshot.error) displayError(el.authError, snapshot.error);
    if (snapshot.step === "STARTING") {
      showAuthStatus("Connecting securely to Telegram…");
      return;
    }
    if (snapshot.step === "WAITING_FOR_QR") {
      clearError(el.authError);
      showAuthStatus("Scan this code with Telegram. It refreshes automatically when needed.");
      if (snapshot.qrLink && snapshot.qrLink !== state.lastQrLink) {
        state.lastQrLink = snapshot.qrLink;
        el.qrCode.innerHTML = '<div class="qr-placeholder"><span class="spinner dark"></span></div>';
        try {
          const result = await request("/api/auth/qr-image");
          el.qrCode.innerHTML = `<img src="${result.image}" alt="Telegram login QR code">`;
          el.qrFallback.textContent = snapshot.qrLink;
          el.qrFallback.hidden = true;
        } catch (error) {
          el.qrFallback.textContent = snapshot.qrLink;
          el.qrFallback.hidden = false;
        }
      }
      el.qrExpiry.textContent = "Waiting for Telegram to confirm the QR code…";
      return;
    }
    if (snapshot.step === "WAITING_FOR_CODE") {
      switchAuthTab("phone");
      showPhoneForm("code");
      showAuthStatus("Telegram sent a verification code. Enter it below.");
      el.verificationCode.focus();
      return;
    }
    if (snapshot.step === "WAITING_FOR_PASSWORD") {
      switchAuthTab("phone");
      showPhoneForm("password");
      showAuthStatus("Telegram requires your two-step verification password.");
      el.twoFactorPassword.focus();
      return;
    }
    if (snapshot.step === "READY") {
      clearError(el.authError);
      showAuthStatus("");
      stopAuthPolling();
      showDrive(snapshot);
      return;
    }
    if (snapshot.step === "ERROR") {
      stopAuthPolling();
      showAuthStatus("");
    }
  }

  function showDrive(snapshot) {
    if (window.location.pathname !== "/home") {
      window.location.replace(homeUrl());
      return;
    }
    if (state.driveVisible) return;
    resetUnauthenticatedUi();
    document.body.classList.remove("auth-page");
    state.driveVisible = true;
    el.authView.classList.add("hidden");
    el.driveView.classList.add("visible");
    const name = snapshot.accountName || "Telegram";
    el.accountInitials.textContent = name.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toUpperCase() || "TG";
    document.title = "TeleDrive — My drive";
    loadFolder(state.currentFolder, false);
  }

  async function loadFolder(folderId = "root", push = true) {
    if (!state.driveVisible) return;
    hideContextMenu();
    clearError(el.driveError);
    el.driveLoading.hidden = false;
    el.itemsGrid.hidden = true;
    el.emptyState.hidden = true;
    try {
      const listing = await request(`/api/drive/items?parentId=${encodeURIComponent(folderId)}`);
      state.currentFolder = folderId || "root";
      state.items = listing.items || [];
      state.breadcrumbs = listing.breadcrumbs || [];
      state.selected.clear();
      if (push) {
        const url = homeUrl(state.currentFolder);
        history.pushState({ folderId: state.currentFolder }, "", url);
      }
      renderDrive();
      refreshStorage();
    } catch (error) {
      displayError(el.driveError, error.message);
      if (/sign in/i.test(error.message)) location.replace("/login");
    } finally {
      el.driveLoading.hidden = true;
    }
  }

  function renderDrive() {
    const current = state.breadcrumbs.at(-1);
    el.folderTitle.textContent = current?.name || "My drive";
    document.title = `TeleDrive — ${el.folderTitle.textContent}`;
    renderBreadcrumbs();
    renderItems();
    updateSelectionToolbar();
  }

  function renderBreadcrumbs() {
    const crumbs = [{ id: "root", name: "My drive" }, ...state.breadcrumbs];
    el.breadcrumbs.innerHTML = crumbs.map((crumb, index) => `${index ? '<span class="crumb-separator">/</span>' : ""}<button type="button" data-folder-id="${escapeHtml(crumb.id)}">${escapeHtml(crumb.name)}</button>`).join("");
    el.breadcrumbs.querySelectorAll("button").forEach((button) => button.addEventListener("click", () => loadFolder(button.dataset.folderId)));
  }

  function renderItems() {
    if (!state.items.length) {
      el.itemsGrid.hidden = true;
      el.emptyState.hidden = false;
      return;
    }
    el.emptyState.hidden = true;
    el.itemsGrid.hidden = false;
    el.itemsGrid.classList.toggle("list-view", state.view === "list");
    el.gridButton.classList.toggle("active", state.view === "grid");
    el.listButton.classList.toggle("active", state.view === "list");
    el.itemsGrid.innerHTML = state.items.map((item) => {
      const folder = item.type === "FOLDER";
      return `<article class="drive-item" data-id="${escapeHtml(item.id)}">
        <button class="item-main" type="button" data-open="${escapeHtml(item.id)}">
          <span class="item-icon ${folder ? "" : "file"}"><svg><use href="#${folder ? "i-folder" : "i-file"}"></use></svg></span>
          <span class="item-name" title="${escapeHtml(item.name)}">${escapeHtml(item.name)}</span>
          <span class="item-meta">${folder ? "Folder" : `${formatBytes(item.size)} · ${escapeHtml(fileType(item))}`}</span>
        </button>
        <button class="item-menu" type="button" data-menu="${escapeHtml(item.id)}" aria-label="Actions for ${escapeHtml(item.name)}"><svg><use href="#i-more"></use></svg></button>
        <input class="item-select" type="checkbox" data-select="${escapeHtml(item.id)}" aria-label="Select ${escapeHtml(item.name)}">
      </article>`;
    }).join("");
    el.itemsGrid.querySelectorAll("[data-open]").forEach((button) => button.addEventListener("click", () => {
      const item = findItem(button.dataset.open);
      if (item?.type === "FOLDER") loadFolder(item.id);
      else if (item) toggleSelection(item.id);
    }));
    el.itemsGrid.querySelectorAll("[data-menu]").forEach((button) => button.addEventListener("click", (event) => {
      event.stopPropagation();
      showContextMenu(event, findItem(button.dataset.menu));
    }));
    el.itemsGrid.querySelectorAll("[data-select]").forEach((input) => input.addEventListener("change", () => toggleSelection(input.dataset.select, input.checked)));
  }

  function fileType(item) {
    if (!item.mimeType || item.mimeType === "application/octet-stream") return "File";
    return item.mimeType.split("/").at(-1).toUpperCase();
  }

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "0 B";
    const units = ["B", "KB", "MB", "GB", "TB"];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const value = bytes / Math.pow(1024, index);
    return `${value >= 10 || index === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[index]}`;
  }

  async function refreshStorage() {
    try {
      const summary = await request("/api/drive/storage");
      const used = summary.usedBytes || 0;
      const ratio = Math.max(4, Math.min(100, (used / (2 * 1024 ** 3)) * 100));
      el.storageFill.style.width = `${ratio}%`;
      el.storageSummary.textContent = `${formatBytes(used)} logical storage · ${summary.fileCount || 0} file${summary.fileCount === 1 ? "" : "s"}`;
    } catch { el.storageSummary.textContent = "Storage unavailable"; }
  }

  function findItem(id) { return state.items.find((item) => item.id === id); }

  function toggleSelection(id, selected) {
    const isSelected = selected === undefined ? !state.selected.has(id) : selected;
    if (isSelected) state.selected.add(id); else state.selected.delete(id);
    el.itemsGrid.querySelectorAll(".drive-item").forEach((node) => {
      const active = state.selected.has(node.dataset.id);
      node.classList.toggle("selected", active);
      const check = node.querySelector(".item-select");
      if (check) check.checked = active;
    });
    updateSelectionToolbar();
  }

  function updateSelectionToolbar() {
    const count = state.selected.size;
    el.selectionToolbar.hidden = count === 0;
    el.selectionCount.textContent = `${count} selected`;
  }

  function showContextMenu(event, item) {
    if (!item) return;
    const commands = [
      ["rename", "i-edit", "Rename"], ["move", "i-move", "Move"], ["copy", "i-copy", "Make a copy"], ["delete", "i-trash", "Delete", "danger"]
    ];
    el.contextMenu.innerHTML = commands.map(([action, icon, label, danger = ""]) => `<button type="button" class="${danger}" data-action="${action}"><svg><use href="#${icon}"></use></svg>${label}</button>`).join("");
    el.contextMenu.hidden = false;
    const x = Math.min(event.clientX, window.innerWidth - 180);
    const y = Math.min(event.clientY, window.innerHeight - 190);
    el.contextMenu.style.left = `${x}px`;
    el.contextMenu.style.top = `${y}px`;
    el.contextMenu.querySelectorAll("button").forEach((button) => button.addEventListener("click", () => {
      hideContextMenu();
      itemAction(button.dataset.action, [item.id]);
    }));
  }

  function hideContextMenu() { el.contextMenu.hidden = true; }

  function itemAction(action, ids) {
    if (action === "rename") openRenameModal(findItem(ids[0]));
    else if (action === "move" || action === "copy") openTargetModal(action, ids);
    else if (action === "delete") deleteItems(ids);
  }

  function openModal(config) {
    state.modal = config;
    el.modalTitle.textContent = config.title;
    el.modalDescription.textContent = config.description;
    el.modalLabel.textContent = config.label || "Name";
    el.modalInput.value = config.value || "";
    el.modalInput.required = Boolean(config.needsName);
    el.modalInput.hidden = !config.needsName;
    el.modalLabel.hidden = !config.needsName;
    el.targetWrap.hidden = !config.needsTarget;
    el.modalSubmit.textContent = config.submit;
    clearError(el.modalError);
    el.modalBackdrop.hidden = false;
    if (config.needsTarget) loadTargetFolders(config.ids || []);
    if (config.needsName) window.setTimeout(() => el.modalInput.focus(), 40);
  }

  function closeModal() { state.modal = null; el.modalBackdrop.hidden = true; }

  async function loadTargetFolders(excludedIds = []) {
    try {
      const folders = await request("/api/drive/folders");
      const excluded = new Set(excludedIds);
      const options = [{ id: "root", name: "My drive" }, ...folders.filter((folder) => !excluded.has(folder.id))];
      el.targetSelect.innerHTML = options.map((folder) => `<option value="${escapeHtml(folder.id)}">${escapeHtml(folder.name)}</option>`).join("");
      el.targetSelect.value = state.currentFolder;
    } catch (error) { displayError(el.modalError, error.message); }
  }

  function openFolderModal() {
    openModal({ title: "New folder", description: "Give your folder a clear name.", label: "Folder name", submit: "Create folder", needsName: true, needsTarget: false, kind: "folder" });
  }

  function openRenameModal(item) {
    if (!item) return;
    openModal({ title: "Rename item", description: "Choose a new name for this item.", label: "Name", value: item.name, submit: "Save name", needsName: true, needsTarget: false, kind: "rename", item });
  }

  function openTargetModal(kind, ids) {
    openModal({ title: kind === "move" ? "Move to folder" : "Copy to folder", description: kind === "move" ? "Choose where the selected items should go." : "Choose where the copied items should be placed.", submit: kind === "move" ? "Move items" : "Copy items", needsName: false, needsTarget: true, kind, ids });
  }

  async function submitModal(event) {
    event.preventDefault();
    const modal = state.modal;
    if (!modal) return;
    clearError(el.modalError);
    el.modalSubmit.disabled = true;
    try {
      if (modal.kind === "folder") {
        await request("/api/drive/folders", json("POST", { parentId: state.currentFolder, name: el.modalInput.value }));
        toast("Folder created");
      } else if (modal.kind === "rename") {
        await request(`/api/drive/items/${encodeURIComponent(modal.item.id)}`, json("PATCH", { name: el.modalInput.value }));
        toast("Name updated");
      } else {
        for (const id of modal.ids) {
          await request(`/api/drive/items/${encodeURIComponent(id)}/${modal.kind}`, json("POST", { targetParentId: el.targetSelect.value }));
        }
        toast(modal.kind === "move" ? "Items moved" : "Copies created");
      }
      closeModal();
      await loadFolder(state.currentFolder, false);
    } catch (error) {
      displayError(el.modalError, error.message);
    } finally {
      el.modalSubmit.disabled = false;
    }
  }

  async function deleteItems(ids) {
    if (!ids.length || !window.confirm(`Delete ${ids.length === 1 ? "this item" : `${ids.length} items`} and everything inside selected folders?`)) return;
    try {
      for (const id of ids) await request(`/api/drive/items/${encodeURIComponent(id)}`, { method: "DELETE" });
      toast(ids.length === 1 ? "Item deleted" : "Items deleted");
      await loadFolder(state.currentFolder, false);
    } catch (error) { displayError(el.driveError, error.message); }
  }

  async function handleUpload(files, folderMode = false) {
    if (!files?.length) return;
    const data = new FormData();
    data.append("parentId", state.currentFolder);
    Array.from(files).forEach((file) => data.append("files", file, folderMode ? (file.webkitRelativePath || file.name) : file.name));
    showUploadProgress(folderMode ? "Uploading folder" : "Uploading files", files.length);
    try {
      await request(folderMode ? "/api/drive/upload-folder" : "/api/drive/upload", { method: "POST", body: data });
      completeUploadProgress();
      toast(folderMode ? "Folder uploaded" : "Files uploaded");
      await loadFolder(state.currentFolder, false);
    } catch (error) {
      hideUploadProgress();
      displayError(el.driveError, error.message);
    } finally {
      el.fileInput.value = "";
      el.directoryInput.value = "";
    }
  }

  function showUploadProgress(title, count) {
    el.uploadProgress.hidden = false;
    el.uploadTitle.textContent = title;
    if (el.uploadPercent) el.uploadPercent.textContent = "Working";
    el.uploadFill.style.width = "28%";
    el.uploadDescription.textContent = `Sending ${count} item${count === 1 ? "" : "s"} securely to Telegram…`;
  }

  function completeUploadProgress() {
    el.uploadFill.style.width = "100%";
    if (el.uploadPercent) el.uploadPercent.textContent = "Done";
    el.uploadDescription.textContent = "Upload complete.";
    window.setTimeout(hideUploadProgress, 1200);
  }

  function hideUploadProgress() { el.uploadProgress.hidden = true; }

  async function search() {
    const query = el.globalSearch.value.trim();
    el.clearSearch.hidden = !query;
    if (!query) { el.searchResults.hidden = true; return; }
    try {
      const items = await request(`/api/drive/search?q=${encodeURIComponent(query)}`);
      el.searchResults.innerHTML = items.length ? items.map((item) => `<button class="search-result" type="button" data-id="${escapeHtml(item.id)}"><svg><use href="#${item.type === "FOLDER" ? "i-folder" : "i-file"}"></use></svg><span>${escapeHtml(item.name)}<small>${item.type === "FOLDER" ? "Folder" : formatBytes(item.size)}</small></span></button>`).join("") : '<div class="search-result"><span>No matching files or folders.</span></div>';
      el.searchResults.hidden = false;
      el.searchResults.querySelectorAll("[data-id]").forEach((button) => button.addEventListener("click", async () => {
        const item = items.find((candidate) => candidate.id === button.dataset.id);
        el.searchResults.hidden = true;
        el.globalSearch.value = "";
        el.clearSearch.hidden = true;
        if (!item) return;
        if (item.type === "FOLDER") await loadFolder(item.id);
        else await loadFolder(item.parentId);
      }));
    } catch (error) { displayError(el.driveError, error.message); }
  }

  async function logout() {
    if (!window.confirm("Log out of this Telegram account on TeleDrive?")) return;
    try { await request("/api/auth/logout", { method: "POST" }); } catch { /* session may already be gone */ }
    stopAuthPolling();
    state.driveVisible = false;
    state.items = [];
    resetUnauthenticatedUi();
    el.driveView.classList.remove("visible");
    el.authView.classList.remove("hidden");
    window.location.replace("/login");
  }

  function bindEvents() {
    el.credentialsToggle.addEventListener("click", toggleCredentials);
    el.credentialsForm.addEventListener("submit", (event) => {
      event.preventDefault();
      try { credentials(); clearError(el.authError); showAuthStatus("API settings ready. Choose a sign-in method."); }
      catch (error) { displayError(el.authError, error.message); }
    });
    el.qrTab.addEventListener("click", () => { switchAuthTab("qr"); startQrLogin(); });
    el.phoneTab.addEventListener("click", () => switchAuthTab("phone"));
    el.refreshQr.addEventListener("click", startQrLogin);
    el.phoneForm.addEventListener("submit", startPhoneLogin);
    el.codeForm.addEventListener("submit", submitCode);
    el.passwordForm.addEventListener("submit", submitPassword);
    document.querySelectorAll("[data-auth-back]").forEach((button) => button.addEventListener("click", () => { showPhoneForm("phone"); clearError(el.authError); }));

    el.newMenuButton.addEventListener("click", () => { el.newMenu.hidden = !el.newMenu.hidden; });
    el.newMenu.addEventListener("click", (event) => {
      const action = event.target.closest("[data-new-action]")?.dataset.newAction;
      if (!action) return;
      el.newMenu.hidden = true;
      if (action === "folder") openFolderModal();
      if (action === "file") el.fileInput.click();
      if (action === "directory") el.directoryInput.click();
    });
    el.myDrive.addEventListener("click", () => loadFolder("root"));
    el.toolbarFolder.addEventListener("click", openFolderModal);
    el.toolbarUpload.addEventListener("click", () => el.fileInput.click());
    document.querySelectorAll("[data-empty-action]").forEach((button) => button.addEventListener("click", () => button.dataset.emptyAction === "folder" ? openFolderModal() : el.fileInput.click()));
    el.fileInput.addEventListener("change", () => handleUpload(el.fileInput.files));
    el.directoryInput.addEventListener("change", () => handleUpload(el.directoryInput.files, true));
    el.gridButton.addEventListener("click", () => { state.view = "grid"; localStorage.setItem("teledrive-view", state.view); renderItems(); });
    el.listButton.addEventListener("click", () => { state.view = "list"; localStorage.setItem("teledrive-view", state.view); renderItems(); });
    el.storageRefresh.addEventListener("click", refreshStorage);
    el.sync.addEventListener("click", () => loadFolder(state.currentFolder, false));
    el.logout.addEventListener("click", logout);
    el.sidebarToggle.addEventListener("click", () => el.sidebar.classList.toggle("open"));
    el.modalForm.addEventListener("submit", submitModal);
    el.modalClose.addEventListener("click", closeModal);
    el.modalCancel.addEventListener("click", closeModal);
    el.modalBackdrop.addEventListener("click", (event) => { if (event.target === el.modalBackdrop) closeModal(); });
    document.querySelectorAll("[data-bulk-action]").forEach((button) => button.addEventListener("click", () => {
      const action = button.dataset.bulkAction;
      if (action === "clear") { state.selected.clear(); renderItems(); updateSelectionToolbar(); }
      else itemAction(action, [...state.selected]);
    }));
    el.globalSearch.addEventListener("input", () => { window.clearTimeout(state.searchTimer); state.searchTimer = window.setTimeout(search, 260); });
    el.clearSearch.addEventListener("click", () => { el.globalSearch.value = ""; el.clearSearch.hidden = true; el.searchResults.hidden = true; el.globalSearch.focus(); });
    document.addEventListener("click", (event) => {
      if (!el.contextMenu.contains(event.target)) hideContextMenu();
      if (!el.newMenu.contains(event.target) && event.target !== el.newMenuButton) el.newMenu.hidden = true;
      if (!el.topbarSearchWrap?.contains?.(event.target) && !el.searchResults.contains(event.target) && event.target !== el.globalSearch) el.searchResults.hidden = true;
    });
    window.addEventListener("popstate", () => { if (state.driveVisible) loadFolder(folderFromLocation(), false); });
  }

  async function initialize() {
    bindEvents();
    try {
      const snapshot = await request("/api/auth/state");
      if (snapshot.step === "READY") showDrive(snapshot);
      else {
        resetUnauthenticatedUi();
        if (window.location.pathname !== "/login") {
          window.location.replace("/login");
          return;
        }
        if (snapshot.step && snapshot.step !== "IDLE" && snapshot.step !== "LOGGED_OUT") { renderAuth(snapshot); beginAuthPolling(); }
        else el.qrExpiry.textContent = "Add your API settings, then select Scan QR code or Phone number.";
      }
    } catch {
      resetUnauthenticatedUi();
      if (window.location.pathname !== "/login") window.location.replace("/login");
      else el.qrExpiry.textContent = "Add your API settings to begin.";
    }
  }

  initialize();
})();
