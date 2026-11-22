# Chimi's Menu (Fabric, Minecraft 26.2)

Client-side mod. Press G to open the menu.

- Auction: pick the item from your inventory, set any duration (seconds or presets), start. Incoming "X paid you AMOUNT" messages count as bids; highest total wins.
- Profit: live HUD showing your real balance change this session. Reads the balance line on the right-side scoreboard and your /bal replies. DonutSMP API is optional (useApi in config, off by default).
  Type /api in game; the key is captured automatically or paste it in the Profit tab.
- Sky: preset selection (rendering hook not added yet).
- Inventory: add an image from your PC (Add image button or drag a file onto the menu) as your inventory background; darkness and scope options.
- Customize: accent color, panel opacity, HUD corner. Saved to config/chimismenu.json.

Requires Java 25, Fabric Loader 0.19.5+, Fabric API.
Build: `gradle build` -> build/libs/chimis-menu-1.0.0.jar

## v1.2 changes
- New full-screen style menu: sidebar pages (Overview, Auction, Profits, Customize, Inventory, Sky), rounded cards, bottom stats strip.
- Overview: greeting, Today / This week / This month earnings (saved per day in config), recent payments.
- Auction: set-up card (name, starting bid, worth, start delay, duration picker), inventory item picker with search,
  live status card with progress bar and leaderboard, Announce button (one chat message per click).
- Customize: HUD themes (Amethyst, Midnight, Ruby, Ocean, Sunset, Mono), theme colour dots, panel opacity, HUD corner, live HUD preview.
- Inventory: thumbnail gallery of saved backgrounds, Add / Clear / Delete, drag and drop still works.
- Sky: Aurora, Galaxy, Sunset, Neon, Blood Moon drawn as a world-fixed star and nebula overlay (not the real sky renderer).

## v1.3 changes
- Spotify page (Windows desktop app): shows the current song on a HUD and in the menu; Previous / Play-Pause / Skip buttons and keys ([ and ] by default, change in Controls). Reads the Spotify window title and sends media keys, no login or API key.
- Customize: edit the text of the Profit HUD ({profit} {perhour}) and the Spotify HUD ({title} {artist}) for playing and paused. Idle text is on the Spotify page.
- Outline page: block highlight with styles Outline+Fill / Fill only / Outline, thickness Thin / Normal / Thick / Chunky, fill strength Light / Medium / Strong, 15 outline colours and 15 fill colours incl. fading Rainbow. Off by default; turn on in the menu.
- Crosshair page: Vanilla plus 14 styles (cross, thick cross, gap cross, cross + dot, dot, circle, circle dot, X, square, T, arrow, heart, diamond, chevron), sizes Small / Medium / Big, 11 colours incl. Rainbow.

## v1.4 (in progress)
- Step 1: Auction rebuilt. New menu (U) with item grid, display name, timer, theme, HUD scale, board size, bid alerts, Test Bid / Test Win and Start / End now / +15s / Cancel. K starts or ends. In-game auction HUD with timer bar, top bidders and bid pop-ups.
- Step 2: Inventory background now fills only the inventory panel (not the whole screen) and sits behind your items. Works in the inventory, crafting table, chests, furnaces and every other inventory-style screen (new "Scope: All screens" default). Every slot gets a box so you can see your inventory.
- Step 3: Sky rebuilt. The sky is painted over every visible sky pixel (gradient, aurora curtains, galaxy band, stars, moon) and hidden behind terrain, trees and roofs using a live horizon scan. 8 presets: Aurora, Galaxy, Golden Sunset, Pink Dusk, Neon, Blood Moon, Midnight. Uses your real game FOV.
- Step 4: Block outline is now ON by default (it was off, so it looked like it did nothing). New defaults match the video: strong magenta fill on the face you look at plus a thin outline. Change style/colours on the Outline page.
- Step 5: HUD editor (Customize > Edit HUD positions): drag the Profit, Spotify and Auction boxes, resize with - / +, nudge with arrows, Reset. Positions and sizes are saved.
- Step 6: Menu size (Customize > Menu size): 70% / 85% / 100% / 125% / 150% for the menus (main menu and auction menu). Larger sizes are capped so the menu still fits your window.
