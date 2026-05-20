# RollWithIt

**Stop rerolling your bounty pearls by hand. Tell RollWithIt what you want — it keeps rolling until it gets it.**

A client-side addon for **[Vault Hunters: Third Edition](https://www.curseforge.com/minecraft/modpacks/vault-hunters-1-18-2)** that adds a "Super Refresh" loop to the bounty table. Set a filter for the task type / task value / reward item you actually want, click one button, and let the mod spin the reroll for you until a matching bounty drops or you run out of pearls.

---

## Features

- **Super Refresh button** added next to the vanilla VH reroll button on the bounty table.
- **Filter screen** with two tabs (Tasks & Rewards), populated from VH's live config so it always reflects what's actually rollable at your current vault level.
- **AND across sides, OR within a side** — e.g. `(Kill Creeper OR Kill Zombie) AND (gets you a Vault Diamond OR Knowledge Star)`.

## How to use

1. Open a **bounty table** in VH.
2. Click the **⚙ Configure** button (next to Super Refresh).
3. Pick the task types / task values / reward items you'd be happy with. (Nothing selected on a side = "any" — that side is ignored.)
4. Set max attempts if you don't like the default.
5. **Select an available bounty** in the right pane.
6. Hit **Super Refresh** and walk away.

The button is disabled when there's nothing to reroll, when no bounty is selected, or when the pearl slot is empty.

## Client-side / server-side

RollWithIt is **client-only**. It never sends mod-specific packets to the server.

## FAQ

**Does this give me free rerolls?**
No. Every reroll costs the same bounty pearls VH would normally charge. The mod stops the loop the instant the pearl slot can't pay for the next attempt.

**Does it work on servers I don't own?**
Yes — the server doesn't need RollWithIt installed. It only sees normal VH reroll requests.

**Can I get banned for using it?**
That depends entirely on your server's rules. Mechanically it does nothing a player couldn't do by spam-clicking the vanilla reroll button; check with your server's admins if you're unsure.

**Why didn't my filter list include `<thing>`?**
The filter options are pulled live from VH's configs and gated by your current vault level. If you can't roll it yet at your level, it won't appear. Level up and re-open Configure.

## License

See [`LICENSE`](LICENSE) for details.
