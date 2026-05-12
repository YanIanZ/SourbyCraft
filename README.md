<p align="center">
  <img src="https://img.shields.io/badge/minecraft-1.21.11-brightgreen?style=flat-square" alt="Minecraft 1.21.11">
  <img src="https://img.shields.io/badge/java-21%2B-blue?style=flat-square" alt="Java 21+">
  <img src="https://img.shields.io/badge/build-paperweight-orange?style=flat-square" alt="Paperweight">
</p>

<h1 align="center">🍞 SourbyCraft</h1>

<p align="center"><em>A high-performance Minecraft server fork of <a href="https://github.com/PaperMC/Paper">Paper</a> and <a href="https://github.com/pufferfish-gg/Pufferfish">Pufferfish</a> with additional patches.</em></p>

---

## Features

- **Adventure translatable components** — items support Adventure's translatable component system
- **Lore newline splitting** — split item lore on newlines at the protocol level
- **Configurable gossip limits** — fine-tune villager gossip per type
- **Instant locale refresh** — refresh various data immediately on player locale change
- **Detailed brand info** — version info in F3 debug screen
- **Configurable Pufferfish config** — custom config location via startup args
- **Performance & fixes** — various optimizations and bug fixes

## Building

> Building on Windows is not supported. Use [WSL](https://learn.microsoft.com/en-us/windows/wsl/install).

```bash
git clone https://github.com/YanIanZ/SourbyCraft.git
cd SourbyCraft
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

The paperclip jar will be at `sourbycraft-server/build/libs/sourbycraft-paperclip-*-mojmap.jar`.

## Running

```bash
java -jar sourbycraft-paperclip-*.jar --nogui
```

## License

SourbyCraft patches are licensed under [MIT](LICENCE.txt).  
Paper and Pufferfish upstreams retain their respective licenses.
