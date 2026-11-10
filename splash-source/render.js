const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');
const { execSync } = require('child_process');

const targetWidth = parseInt(process.argv[2]) || 1080;
const targetHeight = parseInt(process.argv[3]) || 1920;
const fps = parseInt(process.argv[4]) || 60;
const duration = 6.0; 
const totalFrames = Math.floor(duration * fps);

const framesDir = path.join(__dirname, 'frames');
const outputVideo = path.join(__dirname, `splash_${targetWidth}x${targetHeight}.mp4`);

if (fs.existsSync(framesDir)) {
    fs.rmSync(framesDir, { recursive: true, force: true });
}
fs.mkdirSync(framesDir);

(async () => {
    console.log(`[1/3] 启动浏览器，目标分辨率: ${targetWidth}x${targetHeight}, 帧率: ${fps}`);
    
    const browser = await puppeteer.launch({ 
        headless: 'new',
        args: ['--no-sandbox', '--disable-setuid-sandbox', '--proxy-server=http://127.0.0.1:7890'] 
    });
    const page = await browser.newPage();
    
    // 【新增】监听浏览器内的控制台输出
    page.on('console', msg => {
        console.log(`[浏览器日志] ${msg.type().toUpperCase()}: ${msg.text()}`);
    });
    // 【新增】监听浏览器内的未捕获异常
    page.on('pageerror', error => {
        console.error(`[浏览器异常] ${error.message}`);
    });
    // 【新增】监听网络请求失败
    page.on('requestfailed', request => {
        console.error(`[网络失败] ${request.url()} - ${request.failure().errorText}`);
    });

    await page.setViewport({ width: targetWidth, height: targetHeight, deviceScaleFactor: 1 });

    const htmlPath = `file://${path.join(__dirname, 'icon.html')}`;
    // 【修改】将 networkidle0 改为 domcontentloaded，避免因为字体加载慢导致整个等待超时
    await page.goto(htmlPath, { waitUntil: 'domcontentloaded' });

    console.log(`[2/3] 等待渲染函数准备就绪...`);

    try {
        // 增加超时等待到 60 秒，并在加载期间每秒打印一次状态
        await page.waitForFunction('typeof window.renderAtTime === "function"', { timeout: 60000, polling: 1000 });
        console.log('渲染函数已就绪！');
    } catch (e) {
        console.error('等待超时！可能是页面内的 JS 报错导致 window.renderAtTime 没有被定义。请查看上面的浏览器日志/异常。');
        await browser.close();
        process.exit(1);
    }

    await page.evaluate((w, h) => {
        window.setCameraForResolution(w, h);
    }, targetWidth, targetHeight);

    await page.evaluate(() => {
        window.enableExportMode();
    });

    console.log(`[3/3] 开始逐帧截取，总计 ${totalFrames} 帧...`);

    for (let i = 0; i < totalFrames; i++) {
        const currentTime = i / fps;
        
        await page.evaluate((t) => {
            window.renderAtTime(t);
        }, currentTime);

        const framePath = path.join(framesDir, `frame_${String(i).padStart(4, '0')}.png`);
        await page.screenshot({ path: framePath, omitBackground: true }); 

        if (i % 10 === 0 || i === totalFrames - 1) {
            process.stdout.write(`\r进度: ${((i + 1) / totalFrames * 100).toFixed(1)}% (${i + 1}/${totalFrames})`);
        }
    }
    
    console.log(`\n截帧完成！图片保存在: ${framesDir}`);
    await browser.close();

    console.log(`正在调用 FFmpeg 合成视频...`);

    const ffmpegCmd = `ffmpeg -y -framerate ${fps} -i "${framesDir}/frame_%04d.png" -c:v libx264 -crf 18 -pix_fmt yuv420p "${outputVideo}"`;

    try {
        execSync(ffmpegCmd, { stdio: 'inherit' });
        console.log(`\n🎉 合成成功！视频已保存至: ${outputVideo}`);
    } catch (error) {
        console.error(`\n❌ FFmpeg 合成失败:`, error.message);
    }
})();