// Pure parsers for the watch page minus the player — the /next video metadata + description, and
// comment / reply pages (entity-backed commentViewModels). Commands: videoDetails.ts.

import { channelUrl } from "./parse";
import { absImageUrl, asString, get, isObj, textOf, thumbsOf, walk } from "./scan";
import type { WireChip, WireComment, WireCommentPage, WireFactoid, WireVideoDetails } from "./wire";

/** The token a continuation item (an auto-trigger or a "Show more" button) carries. */
function continuationToken(item: unknown): string | undefined {
  return (
    asString(get(item, "continuationEndpoint", "continuationCommand", "token")) ??
    asString(get(item, "button", "buttonRenderer", "command", "continuationCommand", "token"))
  );
}

function factoidOf(node: unknown, publishDate: string | undefined): WireFactoid | undefined {
  const upload = get(node, "uploadTimeFactoidRenderer", "factoid", "factoidRenderer");
  const f =
    upload ??
    get(node, "factoidRenderer") ??
    get(node, "viewCountFactoidRenderer", "factoid", "factoidRenderer");
  const value = textOf(get(f, "value"));
  const label = textOf(get(f, "label"));
  if (value === undefined || label === undefined) return undefined;
  // A same-day upload's factoid is only relative ("4h" / "Ago"); the date sits on the header.
  if (upload !== undefined && publishDate !== undefined) {
    return {
      "value": publishDate,
      "label": asString(get(upload, "accessibilityText")) ?? value + " " + label,
    };
  }
  return { "value": value, "label": label };
}

function setOwner(out: WireVideoDetails, owner: unknown): void {
  if (!isObj(owner)) return;
  const name = textOf(owner["title"]) ?? textOf(owner["attributedTitle"]);
  if (name !== undefined) out["channelTitle"] = name;
  const id = asString(get(owner, "navigationEndpoint", "browseEndpoint", "browseId"));
  if (id !== undefined) out["channelUrl"] = channelUrl(id);
  const thumbs = thumbsOf(owner["thumbnail"]);
  if (thumbs !== undefined) out["channelThumbs"] = thumbs;
  const subscribers = textOf(owner["subscriberCountText"]);
  if (subscribers !== undefined) out["subscribers"] = subscribers;
}

/** A collaboration's owner has no channel link or avatar; the header holds its primary channel. */
function setHeaderChannel(out: WireVideoDetails, header: Record<string, unknown>): void {
  if (out["channelTitle"] === undefined) {
    const name = textOf(header["channel"]);
    if (name !== undefined) out["channelTitle"] = name;
  }
  if (out["channelUrl"] === undefined) {
    const id = asString(get(header, "channelNavigationEndpoint", "browseEndpoint", "browseId"));
    if (id !== undefined) out["channelUrl"] = channelUrl(id);
  }
  if (out["channelThumbs"] === undefined) {
    const thumbs = thumbsOf(header["channelThumbnail"]);
    if (thumbs !== undefined) out["channelThumbs"] = thumbs;
  }
}

// A likeEndpoint's params field by the status it sets.
const RATE_PARAMS: Record<string, string> = {
  "LIKE": "likeParams",
  "DISLIKE": "dislikeParams",
  "INDIFFERENT": "removeLikeParams",
};

/** Collect the likeEndpoints a toggle button's taps carry into `tokens` — skipping a sign-in
 *  modal, whose Sign-in button nests the endpoint to run after sign-in. */
function collectRateTokens(toggle: unknown, tokens: Record<string, string>): void {
  walk(toggle, (key, value) => {
    if (key === "modalEndpoint") return true;
    if (key !== "likeEndpoint") return;
    const status = asString(get(value, "status"));
    const field = status !== undefined ? RATE_PARAMS[status] : undefined;
    const params = field !== undefined ? asString(get(value, field)) : undefined;
    if (status !== undefined && params !== undefined && tokens[status] === undefined) {
      tokens[status] = params;
    }
    return true;
  });
}

function setRating(out: WireVideoDetails, vm: Record<string, unknown>): void {
  const like = get(vm, "likeButtonViewModel", "likeButtonViewModel");
  const status = asString(get(like, "likeStatusEntity", "likeStatus"));
  if (status !== undefined) out["likeStatus"] = status;
  const toggle = get(like, "toggleButtonViewModel", "toggleButtonViewModel");
  const count = asString(get(toggle, "defaultButtonViewModel", "buttonViewModel", "title"));
  if (count !== undefined && count !== "") out["likeCount"] = count;
  const liked = asString(get(toggle, "toggledButtonViewModel", "buttonViewModel", "title"));
  if (liked !== undefined && liked !== "") out["likeCountLiked"] = liked;
  const tokens: Record<string, string> = {};
  collectRateTokens(toggle, tokens);
  collectRateTokens(
    get(vm, "dislikeButtonViewModel", "dislikeButtonViewModel", "toggleButtonViewModel"),
    tokens,
  );
  if (tokens["LIKE"] !== undefined) out["rateTokens"] = tokens;
}

export function videoDetailsOf(root: Record<string, unknown>): WireVideoDetails {
  const out: WireVideoDetails = {};
  walk(root, (key, value) => {
    if (!isObj(value)) return;
    if (key === "videoPrimaryInfoRenderer") {
      const title = textOf(value["title"]);
      if (title !== undefined) out["title"] = title;
      return;
    }
    if (key === "segmentedLikeDislikeButtonViewModel") {
      setRating(out, value);
      return true;
    }
    if (key === "videoDescriptionHeaderRenderer") {
      setHeaderChannel(out, value);
      const publishDate = textOf(value["publishDate"]);
      const facts = value["factoid"];
      if (Array.isArray(facts)) {
        const list: WireFactoid[] = [];
        for (const f of facts) {
          const factoid = factoidOf(f, publishDate);
          if (factoid !== undefined) list.push(factoid);
        }
        if (list.length > 0) out["factoids"] = list;
      }
      // Premieres can come without factoids; the header's localized lines stand in, label-less.
      if (out["factoids"] === undefined) {
        const lines: WireFactoid[] = [];
        for (const line of [textOf(value["views"]), publishDate]) {
          if (line !== undefined) lines.push({ "value": line, "label": "" });
        }
        if (lines.length > 0) out["factoids"] = lines;
      }
      return true;
    }
    if (key === "expandableVideoDescriptionBodyRenderer") {
      const body = textOf(value["attributedDescriptionBodyText"]);
      if (body !== undefined) out["description"] = body;
      return true;
    }
    if (key === "videoSecondaryInfoRenderer") {
      setOwner(out, get(value, "owner", "videoOwnerRenderer"));
      if (out["description"] === undefined) {
        const body = textOf(value["attributedDescription"]);
        if (body !== undefined) out["description"] = body;
      }
      return true;
    }
    if (key === "itemSectionRenderer" && value["sectionIdentifier"] === "comment-item-section") {
      const contents = value["contents"];
      if (Array.isArray(contents)) {
        for (const c of contents) {
          const token = continuationToken(get(c, "continuationItemRenderer"));
          if (token !== undefined) out["commentsContinuation"] = token;
        }
      }
      return true;
    }
    if (
      key === "engagementPanelSectionListRenderer" &&
      value["panelIdentifier"] === "engagement-panel-comments-section"
    ) {
      const count = textOf(
        get(value, "header", "engagementPanelTitleHeaderRenderer", "contextualInfo"),
      );
      if (count !== undefined) out["commentsCount"] = count;
      return true;
    }
    return;
  });
  return out;
}

// Comment text and author live in frameworkUpdates entities, keyed from each commentViewModel.
function entitiesOf(root: Record<string, unknown>): Record<string, Record<string, unknown>> {
  const out: Record<string, Record<string, unknown>> = {};
  const mutations = get(root, "frameworkUpdates", "entityBatchUpdate", "mutations");
  if (!Array.isArray(mutations)) return out;
  for (const m of mutations) {
    const key = asString(get(m, "entityKey"));
    const payload = get(m, "payload");
    if (key !== undefined && isObj(payload)) out[key] = payload;
  }
  return out;
}

/** A toolbar command's comment action token (signed out, the command is a sign-in prompt). */
function commentAction(command: unknown): string | undefined {
  return asString(get(command, "innertubeCommand", "performCommentActionEndpoint", "action"));
}

/** The ⋮ menu item with [icon] (own comments only: EDIT / DELETE), told apart by icon since the
 *  label is localized. */
function menuItem(menu: unknown, icon: string): unknown {
  const items = get(menu, "innertubeCommand", "menuEndpoint", "menu", "menuRenderer", "items");
  if (!Array.isArray(items)) return undefined;
  for (const item of items) {
    const renderer =
      get(item, "menuServiceItemRenderer") ?? get(item, "menuNavigationItemRenderer");
    if (asString(get(renderer, "icon", "iconType")) === icon) return renderer;
  }
  return undefined;
}

/** The first `key` endpoint nested anywhere under `node` (behind dialogs / confirm buttons). */
function nested(node: unknown, key: string): unknown {
  let found: unknown;
  walk(node, (k, v) => {
    if (found !== undefined) return true;
    if (k !== key) return;
    found = v;
    return true;
  });
  return found;
}

// An edit token names its endpoint: a top-level comment and a reply update through different calls.
export const EDIT_COMMENT = "c:";
export const EDIT_REPLY = "r:";

function editToken(menu: unknown): string | undefined {
  const item = menuItem(menu, "EDIT");
  const comment = asString(get(nested(item, "updateCommentEndpoint"), "updateCommentParams"));
  if (comment !== undefined) return EDIT_COMMENT + comment;
  const reply = asString(get(nested(item, "updateCommentReplyEndpoint"), "updateReplyParams"));
  return reply !== undefined ? EDIT_REPLY + reply : undefined;
}

function commentOf(
  vm: unknown,
  entities: Record<string, Record<string, unknown>>,
): WireComment | undefined {
  const commentKey = asString(get(vm, "commentKey"));
  const entity = commentKey !== undefined ? entities[commentKey] : undefined;
  const payload = get(entity, "commentEntityPayload");
  const id = asString(get(payload, "properties", "commentId")) ?? asString(get(vm, "commentId"));
  const text = asString(get(payload, "properties", "content", "content"));
  if (id === undefined || text === undefined) return undefined;
  const comment: WireComment = { "id": id, "text": text };
  const author = get(payload, "author");
  const name = asString(get(author, "displayName"));
  if (name !== undefined) comment["author"] = name;
  const avatar = asString(get(author, "avatarThumbnailUrl"));
  if (avatar !== undefined) comment["authorThumbs"] = [{ "url": absImageUrl(avatar) }];
  const authorId = asString(get(author, "channelId"));
  if (authorId !== undefined) comment["authorChannelUrl"] = channelUrl(authorId);
  if (get(author, "isCreator") === true) comment["byCreator"] = true;
  if (get(author, "isVerified") === true) comment["verified"] = true;
  const published = asString(get(payload, "properties", "publishedTime"));
  if (published !== undefined) comment["published"] = published;
  const likes = asString(get(payload, "toolbar", "likeCountNotliked"));
  if (likes !== undefined && likes.trim() !== "" && likes !== "0") comment["likes"] = likes;
  const replies = asString(get(payload, "toolbar", "replyCount"));
  if (replies !== undefined && replies !== "" && replies !== "0") comment["replyCount"] = replies;
  const pinned = textOf(get(vm, "pinnedText"));
  if (pinned !== undefined && pinned !== "") comment["pinned"] = pinned;
  const likesLiked = asString(get(payload, "toolbar", "likeCountLiked"));
  if (likesLiked !== undefined && likesLiked.trim() !== "" && likesLiked !== "0") {
    comment["likesLiked"] = likesLiked;
  }
  const stateKey = asString(get(vm, "toolbarStateKey"));
  const state = stateKey !== undefined ? entities[stateKey] : undefined;
  const heart = asString(get(state, "engagementToolbarStateEntityPayload", "heartState"));
  if (heart === "TOOLBAR_HEART_STATE_HEARTED") comment["hearted"] = true;
  const likeState = asString(get(state, "engagementToolbarStateEntityPayload", "likeState"));
  if (likeState === "TOOLBAR_LIKE_STATE_LIKED") comment["liked"] = true;
  if (likeState === "TOOLBAR_LIKE_STATE_DISLIKED") comment["disliked"] = true;
  const surfaceKey = asString(get(vm, "toolbarSurfaceKey"));
  const surface = get(
    surfaceKey !== undefined ? entities[surfaceKey] : undefined,
    "engagementToolbarSurfaceEntityPayload",
  );
  const like = commentAction(get(surface, "likeCommand"));
  const unlike = commentAction(get(surface, "unlikeCommand"));
  if (like !== undefined && unlike !== undefined) {
    comment["likeToken"] = like;
    comment["unlikeToken"] = unlike;
  }
  const dislike = commentAction(get(surface, "dislikeCommand"));
  const undislike = commentAction(get(surface, "undislikeCommand"));
  if (dislike !== undefined && undislike !== undefined) {
    comment["dislikeToken"] = dislike;
    comment["undislikeToken"] = undislike;
  }
  const menu = get(surface, "menuCommand");
  const remove = asString(
    get(nested(menuItem(menu, "DELETE"), "performCommentActionEndpoint"), "action"),
  );
  if (remove !== undefined) comment["deleteToken"] = remove;
  const editing = editToken(menu);
  if (editing !== undefined) comment["editToken"] = editing;
  const reply = get(
    surface,
    "replyCommand",
    "innertubeCommand",
    "createCommentReplyDialogEndpoint",
    "dialog",
    "commentReplyDialogRenderer",
  );
  const replyToken = asString(
    get(
      reply,
      "replyButton",
      "buttonRenderer",
      "serviceEndpoint",
      "createCommentReplyEndpoint",
      "createReplyParams",
    ),
  );
  if (replyToken !== undefined) {
    comment["replyToken"] = replyToken;
    const placeholder = textOf(get(reply, "placeholderText"));
    if (placeholder !== undefined) comment["replyPlaceholder"] = placeholder;
  }
  return comment;
}

export function commentPageOf(root: Record<string, unknown>): WireCommentPage {
  const entities = entitiesOf(root);
  const page: WireCommentPage = { "comments": [] };
  walk(root, (key, value) => {
    if (!isObj(value)) return;
    if (key === "frameworkUpdates") return true;
    if (key === "commentsHeaderRenderer") {
      const count = textOf(value["countText"]);
      if (count !== undefined) page["countText"] = count;
      const items = get(value, "sortMenu", "sortFilterSubMenuRenderer", "subMenuItems");
      if (Array.isArray(items)) {
        const sort: WireChip[] = [];
        for (const it of items) {
          const title = asString(get(it, "title"));
          if (title === undefined) continue;
          const chip: WireChip = { "title": title };
          const token = asString(get(it, "serviceEndpoint", "continuationCommand", "token"));
          if (token !== undefined) chip["token"] = token;
          if (get(it, "selected") === true) chip["selected"] = true;
          sort.push(chip);
        }
        if (sort.length > 0) page["sort"] = sort;
      }
      const box = get(value, "createRenderer", "commentSimpleboxRenderer");
      const placeholder = textOf(get(box, "placeholderText"));
      if (placeholder !== undefined) page["composerPlaceholder"] = placeholder;
      const create = asString(
        get(
          box,
          "submitButton",
          "buttonRenderer",
          "serviceEndpoint",
          "createCommentEndpoint",
          "createCommentParams",
        ),
      );
      if (create !== undefined) page["createToken"] = create;
      return true;
    }
    if (key === "commentThreadRenderer") {
      const comment = commentOf(get(value, "commentViewModel", "commentViewModel"), entities);
      if (comment !== undefined) {
        const more = get(value, "replies", "commentRepliesRenderer", "contents");
        if (Array.isArray(more)) {
          for (const c of more) {
            const token = continuationToken(get(c, "continuationItemRenderer"));
            if (token !== undefined) comment["repliesContinuation"] = token;
          }
        }
        page["comments"].push(comment);
      }
      return true;
    }
    if (key === "commentViewModel") {
      const comment = commentOf(value, entities);
      if (comment !== undefined) page["comments"].push(comment);
      return true;
    }
    if (key === "continuationItemRenderer") {
      const token = continuationToken(value);
      if (token !== undefined) page["continuation"] = token;
      return true;
    }
    return;
  });
  return page;
}
