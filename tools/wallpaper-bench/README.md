# 壁纸批量测试台

网页参考渲染器 `we.js`（和 App 的 `WeScene.kt` 一一对应）跑一批 Wallpaper Engine 壁纸：每张统计画出 / 跳过的图层和原因，截图和包里自带的预览图比相似度。改渲染器先在这里改、跑通，再同步到 Kotlin。

官方素材不放进仓库。用的时候现取：

```bash
mkdir -p /home/claude/weapk/x && cd /home/claude/weapk
# 用户放在 ppt 仓库「大肥鱼」发布里的官方安卓版安装包（io.wallpaperengine.weclient）
curl -sL -o base.1.apk "https://github.com/dhhd1426-ops/ppt/releases/download/%E5%A4%A7%E8%82%A5%E9%B1%BC/base.1.apk"
cd x && unzip -qo ../base.1.apk 'assets/shaders/*' 'assets/materials/*' 'assets/models/*' 'assets/scripts/*' 'assets/wallpapers/*'

B=/home/claude/bench; T=$(pwd)/tools/wallpaper-bench   # 在 Amor 仓库根目录执行
mkdir -p $B && cp $T/bench.html $B/ && ln -sf $T/we.js $B/we.js
python3 -I $T/mkpack.py /home/claude/weapk/x/assets $B/engine
for f in /home/claude/weapk/x/assets/wallpapers/*.mpkg 用户发来的.mpkg; do python3 -I $T/mkpack.py "$f" $B/w/$(basename "$f" .mpkg); done
BENCH=$B python3 $T/run.py            # 全部；或 run.py 名字1 名字2
```

`mkpack.py` 和 `tex.py` 要放在同一个目录。素材以外的引擎行为怎么查（原生库逆向）见 [`逆向.md`](逆向.md)。Playwright 已装；浏览器用 swiftshader，支持 ETC2。

第 549 版时的结果：10 张全部加载、着色器全部编译；deep_space 相似度 0.96。画不全的原因只剩粒子（5 张）、3D 模型（4 张）、属性脚本（2 张）。
