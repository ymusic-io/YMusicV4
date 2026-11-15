// Player engine (player.js): WEB Application + MSE leftover. Scrape feed is engine.js.
// boot.ts installs WHATWG fetch/xhr (eval'd base.js + SABR) on load.
import "./console";
import "./transport";
import "./protocol/command";
import "./protocol/http";
import "./protocol/mse";
import "./lifecycle";
import "./logic/player";
import "./logic/yt/user_agent";
