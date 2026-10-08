"use strict";

(() => {
  const byId = (id) => document.getElementById(id);
  const ui = Object.fromEntries([
    "connection", "login-panel", "login-form", "token", "login-button", "login-feedback",
    "chat-panel", "identity", "message-count", "logout-button", "timeline", "empty-state",
    "messages", "message-form", "message", "character-count", "send-button", "send-feedback",
  ].map((id) => [id, byId(id)]));
  const storageKey = "weixu.relay.session-token";
  const roleNames = { user: "用户", codex: "Codex", dot: "Dot" };
  const roleInitials = { user: "我", codex: "C", dot: "D" };
  const rows = new Map();
  const requests = new Set();
  let token = "";
  let role = "";
  let cursor = 0;
  let generation = 0;
  let polling = false;
  let pollTimer;
  let pendingMessage = null;
  let sending = false;

  function connection(state, text) {
    ui.connection.dataset.state = state;
    ui.connection.textContent = text;
  }

  function feedback(target, text, state = "error") {
    target.textContent = text;
    target.dataset.state = state;
  }

  function storeToken(value) {
    try {
      if (value) sessionStorage.setItem(storageKey, value);
      else sessionStorage.removeItem(storageKey);
    } catch {
      // A session still works when browser policy disables session storage.
    }
  }

  async function request(path, options = {}) {
    const controller = new AbortController();
    requests.add(controller);
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const response = await fetch(path, {
        ...options,
        headers: { Authorization: `Bearer ${token}`, ...options.headers },
        signal: controller.signal,
        cache: "no-store",
        credentials: "omit",
        redirect: "error",
      });
      if (!response.ok) {
        const error = new Error(response.status === 401 || response.status === 403
          ? "令牌无效或已失效，请重新登录。"
          : response.status === 429 ? "请求较频繁，请稍后重试。" : `服务暂时不可用（${response.status}），请稍后重试。`);
        error.status = response.status;
        throw error;
      }
      return await response.json();
    } catch (error) {
      if (error.name === "AbortError") throw new Error("连接超时，请稍后重试。");
      if (error instanceof TypeError) throw new Error("暂时无法连接，请检查网络后重试。");
      throw error;
    } finally {
      clearTimeout(timer);
      requests.delete(controller);
    }
  }

  function endSession(message = "") {
    generation += 1;
    clearTimeout(pollTimer);
    for (const controller of requests) controller.abort();
    requests.clear();
    token = "";
    role = "";
    cursor = 0;
    polling = false;
    sending = false;
    pendingMessage = null;
    storeToken("");
    rows.clear();
    ui.messages.replaceChildren();
    ui["message-count"].textContent = "0 条消息";
    ui.message.value = "";
    ui.message.disabled = false;
    ui["character-count"].textContent = "0";
    ui["send-button"].disabled = false;
    ui["send-button"].textContent = "发送消息";
    ui.token.value = "";
    ui.token.disabled = false;
    ui["login-button"].disabled = false;
    ui["login-button"].textContent = "进入对话";
    ui["chat-panel"].hidden = true;
    ui["login-panel"].hidden = false;
    feedback(ui["login-feedback"], message);
    feedback(ui["send-feedback"], "");
    connection(message ? "error" : "idle", message ? "需要重新连接" : "尚未连接");
  }

  function addMessage(message) {
    if (!message || !Number.isSafeInteger(message.seq) || message.seq <= 0 || rows.has(message.seq)) return;
    if (!Object.hasOwn(roleNames, message.sender) || typeof message.text !== "string") return;
    const row = document.createElement("li");
    row.className = "message";
    row.dataset.sender = message.sender;
    row.dataset.seq = String(message.seq);
    const avatar = document.createElement("span");
    avatar.className = "sender-avatar";
    avatar.setAttribute("aria-hidden", "true");
    avatar.textContent = roleInitials[message.sender];
    const body = document.createElement("div");
    const header = document.createElement("div");
    header.className = "message-header";
    const sender = document.createElement("span");
    sender.className = "sender-name";
    sender.textContent = roleNames[message.sender] + (message.sender === role ? " · 当前身份" : "");
    const time = document.createElement("time");
    time.className = "message-time";
    const parsed = new Date(message.created_at);
    if (Number.isFinite(parsed.getTime())) {
      time.dateTime = parsed.toISOString();
      time.textContent = parsed.toLocaleString("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" });
      time.title = parsed.toLocaleString("zh-CN");
    }
    const number = document.createElement("span");
    number.className = "message-number";
    number.textContent = `#${message.seq}`;
    const text = document.createElement("p");
    text.className = "message-text";
    text.textContent = message.text;
    header.append(sender, time, number);
    body.append(header, text);
    row.append(avatar, body);
    const laterRow = Array.from(ui.messages.children).find((item) => Number(item.dataset.seq) > message.seq);
    ui.messages.insertBefore(row, laterRow || null);
    rows.set(message.seq, row);
    ui["empty-state"].hidden = true;
    ui["message-count"].textContent = `${rows.size} 条消息`;
  }

  async function poll() {
    if (polling || !role) return;
    clearTimeout(pollTimer);
    polling = true;
    const session = generation;
    const firstLoad = cursor === 0;
    const followLatest = firstLoad || ui.timeline.scrollHeight - ui.timeline.scrollTop - ui.timeline.clientHeight < 90;
    try {
      let hasMore = true;
      while (hasMore) {
        const previous = cursor;
        const data = await request(`/api/messages?after=${cursor}&limit=100`);
        if (session !== generation) return;
        if (!Array.isArray(data.messages) || !Number.isSafeInteger(data.next_cursor) || data.next_cursor < cursor) {
          throw new Error("消息格式异常，正在等待服务恢复。");
        }
        data.messages.forEach(addMessage);
        cursor = data.next_cursor;
        hasMore = data.has_more === true;
        if (hasMore && cursor <= previous) throw new Error("消息读取暂停，将自动重试。");
      }
      if (followLatest) ui.timeline.scrollTop = ui.timeline.scrollHeight;
      if (!rows.size) {
        ui["empty-state"].hidden = false;
        ui["empty-state"].textContent = "还没有消息，发出第一条协作说明吧。";
      }
      connection("online", "已连接 · 每 5 秒更新");
    } catch (error) {
      if (session !== generation) return;
      if (error.status === 401 || error.status === 403) {
        endSession(error.message);
        return;
      }
      connection("error", "连接中断 · 自动重试");
      if (!rows.size) {
        ui["empty-state"].hidden = false;
        ui["empty-state"].textContent = error.message;
      }
    } finally {
      if (session === generation) {
        polling = false;
        if (role) pollTimer = setTimeout(poll, 5000);
      }
    }
  }

  async function login(value) {
    endSession();
    token = value.trim();
    if (!token) return;
    const session = generation;
    ui.token.disabled = true;
    ui["login-button"].disabled = true;
    ui["login-button"].textContent = "正在连接…";
    connection("busy", "正在连接");
    try {
      const me = await request("/api/me");
      if (session !== generation) return;
      if (!Object.hasOwn(roleNames, me.role)) throw new Error("无法识别当前身份，请检查令牌。");
      role = me.role;
      storeToken(token);
      ui.token.value = "";
      ui["identity"].textContent = `当前身份：${roleNames[role]}`;
      ui["login-panel"].hidden = true;
      ui["chat-panel"].hidden = false;
      ui["empty-state"].hidden = false;
      ui["empty-state"].textContent = "正在读取消息…";
      connection("busy", "正在同步消息");
      ui.message.focus();
      void poll();
    } catch (error) {
      if (session === generation) endSession(error.message);
    }
  }

  function newMessageId() {
    if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
    const bytes = crypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 15) | 64;
    bytes[8] = (bytes[8] & 63) | 128;
    const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0"));
    return `${hex.slice(0, 4).join("")}-${hex.slice(4, 6).join("")}-${hex.slice(6, 8).join("")}-${hex.slice(8, 10).join("")}-${hex.slice(10).join("")}`;
  }

  async function sendMessage() {
    if (!role || sending) return;
    const text = ui.message.value;
    if (!text.trim() || text.length > 8000) return;
    if (!pendingMessage || pendingMessage.text !== text) {
      pendingMessage = { text, client_message_id: newMessageId() };
    }
    const session = generation;
    sending = true;
    ui.message.disabled = true;
    ui["send-button"].disabled = true;
    ui["send-button"].textContent = "正在发送…";
    feedback(ui["send-feedback"], "");
    try {
      const message = await request("/api/messages", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(pendingMessage),
      });
      if (session !== generation) return;
      if (!message || !Number.isSafeInteger(message.seq)) throw new Error("尚未确认发送结果，请点击重试。");
      // Only GET responses advance the cursor: a concurrent post must not hide unread messages.
      addMessage(message);
      ui.timeline.scrollTop = ui.timeline.scrollHeight;
      pendingMessage = null;
      ui.message.value = "";
      ui["character-count"].textContent = "0";
      feedback(ui["send-feedback"], "消息已送达；对方主动读取后才能看到。", "success");
      void poll();
    } catch (error) {
      if (session !== generation) return;
      // Retain the text and id together so retries cannot create duplicate messages.
      feedback(ui["send-feedback"], `${error.message} 内容已保留，点击发送可重试。`);
      connection("error", "发送未确认");
    } finally {
      if (session === generation) {
        sending = false;
        ui.message.disabled = false;
        ui["send-button"].disabled = false;
        ui["send-button"].textContent = pendingMessage ? "重试发送" : "发送消息";
        ui.message.focus();
      }
    }
  }

  ui["login-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    void login(ui.token.value);
  });
  ui["logout-button"].addEventListener("click", () => {
    endSession();
    ui.token.focus();
  });
  ui["message-form"].addEventListener("submit", (event) => {
    event.preventDefault();
    void sendMessage();
  });
  ui.message.addEventListener("input", () => {
    ui["character-count"].textContent = String(ui.message.value.length);
    if (pendingMessage && pendingMessage.text !== ui.message.value) {
      pendingMessage = null;
      ui["send-button"].textContent = "发送消息";
      feedback(ui["send-feedback"], "内容已修改，下次发送将作为新消息。");
    }
  });
  ui.message.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key === "Enter" && !event.isComposing) {
      event.preventDefault();
      void sendMessage();
    }
  });
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && role) void poll();
  });
  try {
    const saved = sessionStorage.getItem(storageKey);
    if (saved) void login(saved);
  } catch {
    // Browser session storage is optional; the token never enters a URL.
  }
})();
