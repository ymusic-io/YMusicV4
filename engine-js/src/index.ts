// Scrape engine (engine.js): feed, search, login, download resolvers.
// Player MSE lives in player-index.ts → player.js. Import order is load order.
import "./console"; // console over the __consoleLog native — first, so boot code can log
import "./transport"; // pipe transport JS half: ymix, __js*, timers
import "./protocol/command"; // commandHandler + JsEngineError (the error contract)
import "./protocol/http"; // HttpRequest over the "http" pipe command
// protocol/cookie (Cookies over "cookie/get"/"cookie/set") has no side effects — it is pulled in by
// whichever scraping module imports { Cookies }, not registered here.
import "./lifecycle"; // "init" health check + "shutdown" (SIGINT as a pipe)
import "./logic"; // scraping logic (no player)
import "./logic/yt/user_agent"; // DESKTOP_UA on every request that sets no User-Agent
