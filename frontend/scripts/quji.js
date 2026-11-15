import { createApi, images } from "./api.js";

const tokenKey = "token";
const clock = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
});
const styles = {
  手作体验: ["clay", "手"],
  城市户外: ["green", "野"],
  阅读交流: ["purple", "读"],
  展览演出: ["yellow", "艺"],
};
const api = createApi({
  tokenProvider: () => sessionStorage.getItem(tokenKey),
  onUnauthorized: (quiet) => {
    sessionStorage.removeItem(tokenKey);
    if (!quiet) app.sessionExpired();
  },
});
const dateFields = ["beginTime", "endTime", "sessionStartsAt", "sessionEndsAt"];

const app = new Vue({
  el: "#app",
  data: {
    view: "discover", eventId: "", backTo: "#discover", epoch: 0,
    user: null, admin: false, busy: false, loading: false, locating: false,
    error: "", notice: "", categories: [], page: 1, total: 0,
    keyword: "", category: "", coords: null, events: [], ranking: [],
    event: null, sessions: [], pending: [], registrations: [], signCount: 0,
    notes: [], notesPage: 1, notesMore: false, following: false, followed: {},
    feedCursor: null, note: { title: "", content: "", files: [] },
    eventForm: {}, sessionForm: {}, admissionCode: "", phone: "", code: "",
  },
  async mounted() {
    window.addEventListener("hashchange", this.route);
    try {
      await Promise.all([this.loadUser(), this.loadCategories()]);
    } catch (error) {
      this.error = error.message;
    }
    await this.route();
    this.poll = setInterval(this.refreshPending, 2500);
  },
  beforeDestroy() {
    window.removeEventListener("hashchange", this.route);
    clearInterval(this.poll);
  },
  methods: {
    images,
    art(category) { return (styles[category] || styles.展览演出)[0]; },
    artMark(category) { return (styles[category] || styles.展览演出)[1]; },
    date(value) {
      if (!value) return "待定";
      const input = typeof value === "string" && !/(?:Z|[+-]\d{2}:?\d{2})$/.test(value)
        ? value.replace(" ", "T") + "Z" : value;
      const time = new Date(input);
      return Number.isNaN(time.getTime()) ? "待定" : clock.format(time);
    },
    distance(meters) {
      return meters < 1000 ? Math.round(meters) + " 米" : (meters / 1000).toFixed(1) + " 公里";
    },
    requestStatus(state) {
      return {
        PROCESSING: "已提交，正在确认名额。",
        SUCCESS: "报名成功，正在更新入场凭证。",
        REVIEW: "报名正在核对，请稍后查看。",
        FAILED: "报名未成功。若还有余量，可以重新报名。",
      }[state] || "正在查询报名结果…";
    },
    scrollCatalog() { document.getElementById("catalog")?.scrollIntoView(); },
    async loadCategories() {
      this.categories = (await api("/events/categories")).data || [];
    },
    async loadUser() {
      if (!sessionStorage.getItem(tokenKey)) return;
      try {
        this.user = (await api("/user/me", { quiet: true })).data;
      } catch (error) {
        if (error.status !== 401) throw error;
        return;
      }
      const key = "quji:pending:" + this.user.id;
      try { this.pending = JSON.parse(sessionStorage.getItem(key) || "[]"); }
      catch { this.pending = []; }
      this.admin = await api("/event-admin/access", { quiet: true }).then(() => true, () => false);
    },
    async route() {
      const match = location.hash.match(/^#(discover|event|community|mine|staff|login)(?:\/(\d+))?$/);
      const active = ++this.epoch;
      this.view = match?.[1] || "discover";
      this.eventId = match?.[2] || "";
      this.event = null;
      this.sessions = [];
      this.notes = [];
      this.notesMore = false;
      this.following = false;
      this.note = { title: "", content: "", files: [] };
      this.eventForm = {};
      this.sessionForm = {};
      this.error = "";
      this.notice = "";
      this.loading = true;
      this.page = 1;
      this.total = 0;
      window.scrollTo(0, 0);
      try {
        if (this.eventId && ["event", "community", "staff"].includes(this.view)) {
          const current = (await api("/events/" + this.eventId)).data;
          if (active !== this.epoch) return;
          this.event = current;
          this.eventForm = { ...current };
        }
        if (this.view === "discover") {
          await Promise.all([
            this.loadEvents(1),
            api("/events/ranking").then(result => {
              if (active === this.epoch) this.ranking = result.data || [];
            }).catch(() => {}),
          ]);
        } else if (this.event && this.view === "event") {
          const result = await api("/events/" + this.eventId + "/sessions");
          if (active === this.epoch) this.sessions = result.data || [];
        } else if (this.view === "community") {
          await this.loadNotes(true);
        } else if (this.view === "mine" && this.user) {
          await Promise.all([this.loadRegistrations(1), this.loadSignCount()]);
        }
      } catch (error) {
        if (active === this.epoch) this.error = error.message;
      } finally {
        if (active === this.epoch) this.loading = false;
      }
    },
    async loadEvents(page) {
      const active = this.epoch;
      const search = this.searchId = (this.searchId || 0) + 1;
      this.loading = true;
      this.error = "";
      try {
        const result = await api("/events", {
          params: { page, keyword: this.keyword, category: this.category, ...this.coords },
        });
        if (active !== this.epoch || search !== this.searchId) return;
        this.events = result.data || [];
        this.total = result.total || 0;
        this.page = page;
      } catch (error) {
        if (active === this.epoch && search === this.searchId) this.error = error.message;
      } finally {
        if (active === this.epoch && search === this.searchId) this.loading = false;
      }
    },
    search() { return this.loadEvents(1); },
    chooseCategory(name) { this.category = name; return this.search(); },
    clearLocation() { this.coords = null; return this.search(); },
    locate() {
      if (!navigator.geolocation) {
        this.error = "当前浏览器无法访问位置，请使用活动搜索。";
        return;
      }
      const active = this.epoch;
      this.locating = true;
      navigator.geolocation.getCurrentPosition(
        ({ coords }) => {
          this.locating = false;
          if (active !== this.epoch) return;
          this.coords = { x: coords.longitude, y: coords.latitude };
          this.search();
        },
        () => {
          this.locating = false;
          if (active === this.epoch) this.error = "获取位置失败，请检查浏览器的位置权限。";
        },
        { timeout: 8000, maximumAge: 30000 },
      );
    },
    needUser() {
      if (this.user) return true;
      this.backTo = location.hash || "#discover";
      location.hash = "#login";
      return false;
    },
    sessionExpired() {
      if (!this.user) return;
      this.user = null;
      this.admin = false;
      this.pending = [];
      this.followed = {};
      this.needUser();
    },
    async action(handler) {
      if (this.busy) return;
      this.busy = true;
      this.notice = "";
      this.error = "";
      try { await handler(); }
      catch (error) { this.error = error.message; }
      finally { this.busy = false; }
    },
    sendCode() {
      return this.action(async () => {
        await api("/user/code", { method: "POST", params: { phone: this.phone } });
        this.notice = "验证码已生成，请查看服务端日志。";
      });
    },
    login() {
      return this.action(async () => {
        const result = await api("/user/login", {
          method: "POST", body: { phone: this.phone, code: this.code },
        });
        sessionStorage.setItem(tokenKey, result.data);
        await this.loadUser();
        this.code = "";
        location.hash = this.backTo;
        this.backTo = "#discover";
      });
    },
    logout() {
      return this.action(async () => {
        await api("/user/logout", { method: "POST", quiet: true }).catch(error => {
          if (error.status !== 401) throw error;
        });
        sessionStorage.removeItem(tokenKey);
        this.user = null;
        this.admin = false;
        this.pending = [];
        this.registrations = [];
        this.followed = {};
        if (!location.hash || location.hash === "#discover") await this.route();
        else location.hash = "#discover";
      });
    },
    persistPending() {
      if (this.user) sessionStorage.setItem("quji:pending:" + this.user.id, JSON.stringify(this.pending));
    },
    removePending(id) {
      this.pending = this.pending.filter(request => request.id !== id);
      this.persistPending();
    },
    register(session) {
      if (!this.needUser()) return;
      return this.action(async () => {
        const { data } = await api("/registrations/session/" + session.id, { method: "POST" });
        const id = String(data);
        if (!this.pending.some(request => request.id === id)) {
          this.pending.push({ id, title: session.title, state: "PROCESSING" });
          this.persistPending();
        }
        this.notice = "报名已提交，可在「我的报名」查看进度。";
        this.refreshPending();
      });
    },
    async refreshPending() {
      if (!this.user || !this.pending.length || this.polling) return;
      const owner = this.user.id;
      this.polling = true;
      try {
        const requests = this.pending.filter(item => !["SUCCESS", "FAILED"].includes(item.state)).slice(0, 8);
        await Promise.all(requests.map(async item => {
          try {
            const { data } = await api("/registrations/requests/" + item.id, { quiet: true });
            if (this.user?.id === owner) item.state = data.state || item.state;
          } catch (error) {
            if (error.status === 401 && this.user?.id === owner) this.sessionExpired();
          }
        }));
        if (this.user?.id === owner) {
          this.persistPending();
          if (this.view === "mine" && this.page === 1 && this.pending.some(item => item.state === "SUCCESS")) {
            await this.loadRegistrations(1, false);
          }
        }
      } finally {
        this.polling = false;
      }
    },
    async loadRegistrations(page, showLoading = true) {
      const active = this.epoch;
      if (showLoading) this.loading = true;
      try {
        const result = await api("/registrations", { params: { page } });
        if (active !== this.epoch) return;
        this.registrations = result.data || [];
        this.total = result.total || 0;
        this.page = page;
        const visible = new Set(this.registrations.map(registration => String(registration.id)));
        this.pending = this.pending.filter(item => !visible.has(item.id));
        this.persistPending();
      } catch (error) {
        if (active === this.epoch) this.error = error.message;
      } finally {
        if (active === this.epoch && showLoading) this.loading = false;
      }
    },
    async loadSignCount() {
      this.signCount = (await api("/user/sign/count")).data || 0;
    },
    signIn() {
      return this.action(async () => {
        await api("/user/sign", { method: "POST" });
        await this.loadSignCount();
        this.notice = "今日打卡成功。";
      });
    },
    copyCode(code) {
      return this.action(async () => {
        await navigator.clipboard.writeText(code);
        this.notice = "已复制入场码。";
      });
    },
    showCommunity(following) {
      if (following && !this.needUser()) return;
      this.following = following;
      return this.loadNotes(true);
    },
    async loadNotes(reset) {
      const active = this.epoch;
      const sequence = this.notesSequence = (this.notesSequence || 0) + 1;
      const fresh = () => this.epoch === active && this.notesSequence === sequence;
      this.loading = true;
      if (reset) {
        this.notes = [];
        this.notesPage = 1;
        this.feedCursor = { lastId: Date.now(), offset: 0 };
      }
      try {
        let loaded;
        if (this.following) {
          const result = await api("/event-feed", { params: this.feedCursor });
          if (!fresh()) return;
          loaded = result.data?.list || [];
          this.notesMore = Boolean(result.data?.minTime);
          this.feedCursor = { lastId: result.data?.minTime, offset: result.data?.offset };
        } else {
          const result = await api("/events/notes", { params: { eventId: this.eventId, page: this.notesPage } });
          if (!fresh()) return;
          loaded = result.data || [];
          this.notesMore = this.notesPage * 10 < result.total;
          this.notesPage++;
        }
        this.notes = this.notes.concat(loaded);
        if (this.user) {
          const authors = [...new Set(loaded.map(note => note.userId))];
          await Promise.all(authors.map(async id => {
            if (id === this.user?.id || Object.hasOwn(this.followed, id)) return;
            const result = await api("/follow/or/not/" + id, { quiet: true }).catch(() => null);
            if (result && fresh()) this.$set(this.followed, id, Boolean(result.data));
          }));
        }
      } catch (error) {
        if (fresh()) this.error = error.message;
      } finally {
        if (fresh()) this.loading = false;
      }
    },
    selectImages(event) {
      const files = Array.from(event.target.files);
      if (files.length > 9 || files.some(file => file.size > 5 * 1024 * 1024)) {
        this.error = "最多选择 9 张图片，每张不超过 5 MB。";
        this.note.files = [];
        event.target.value = "";
        return;
      }
      this.note.files = files;
      this.error = "";
    },
    publishNote(event) {
      if (!this.needUser()) return;
      const form = event.target;
      const eventId = this.eventId;
      const draft = { ...this.note };
      return this.action(async () => {
        const paths = [];
        try {
          for (const file of draft.files) {
            const body = new FormData();
            body.append("file", file);
            paths.push((await api("/upload/blog", { method: "POST", body })).data);
          }
          await api("/events/" + eventId + "/notes", {
            method: "POST", body: { title: draft.title, content: draft.content, images: paths.join(",") },
          });
        } catch (error) {
          await Promise.allSettled(paths.map(name => api("/upload/blog", { method: "DELETE", params: { name } })));
          throw error;
        }
        if (this.view !== "community" || this.eventId !== eventId) return;
        form.reset();
        this.note = { title: "", content: "", files: [] };
        this.notice = "活动体验已发布。";
        await this.loadNotes(true);
      });
    },
    like(note) {
      if (!this.needUser()) return;
      return this.action(async () => {
        const liked = !note.isLike;
        await api("/blog/like/" + note.id + "/state", { method: "PUT", params: { liked } });
        const { data } = await api("/blog/" + note.id);
        note.isLike = data.isLike;
        note.liked = data.liked;
      });
    },
    follow(id) {
      if (!this.needUser()) return;
      return this.action(async () => {
        const next = !this.followed[id];
        await api("/follow/" + id + "/" + next, { method: "PUT" });
        this.$set(this.followed, id, next);
        this.notice = next ? "已关注作者。" : "已取消关注。";
      });
    },
    saveEvent() {
      return this.action(async () => {
        const id = this.eventId;
        const result = await api("/event-admin/events" + (id ? "/" + id : ""), {
          method: id ? "PUT" : "POST", body: this.eventForm,
        });
        if (!id) location.hash = "#staff/" + result.data;
        else this.notice = "活动信息已更新。";
      });
    },
    publishSession() {
      return this.action(async () => {
        const body = { ...this.sessionForm };
        for (const field of dateFields) {
          body[field] = new Date(body[field] + ":00+08:00").toISOString().slice(0, 19);
        }
        await api("/event-admin/events/" + this.eventId + "/sessions", { method: "POST", body });
        this.sessionForm = {};
        this.notice = "场次已保存，报名入口将在发布完成后开放。";
      });
    },
    checkIn() {
      return this.action(async () => {
        await api("/event-admin/check-in", { method: "POST", body: { code: this.admissionCode } });
        this.admissionCode = "";
        this.notice = "核验成功，可以入场。";
      });
    },
  },
});
