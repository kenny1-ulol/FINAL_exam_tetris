import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.swing.*;


public class Tetris extends JPanel implements ActionListener, KeyListener {

    private static final int COLS = 10;
    private static final int ROWS = 20;
    private static final int CELL = 30;
    private static final int SIDE = 170;
    private static final int WIDTH = COLS * CELL + SIDE;
    private static final int HEIGHT = ROWS * CELL;

    private static final Color OUTLINE = Color.CYAN;
    private static final Color GHOST = new Color(90, 90, 90);
    private static final Color GRID = new Color(30, 30, 30);

    private static final int[][][] SHAPES = {
        {{-1, 0}, {0, 0}, {1, 0}, {2, 0}},
        {{0, 0}, {1, 0}, {0, 1}, {1, 1}},
        {{-1, 0}, {0, 0}, {1, 0}, {0, 1}},
        {{0, 0}, {1, 0}, {-1, 1}, {0, 1}},
        {{-1, 0}, {0, 0}, {0, 1}, {1, 1}},
        {{-1, 0}, {0, 0}, {1, 0}, {-1, 1}},
        {{-1, 0}, {0, 0}, {1, 0}, {1, 1}},
    };
    private static final int O_INDEX = 1;

    private static final String[][] TITLE = {
        {"11111", "00100", "00100", "00100", "00100"},
        {"11111", "10000", "11110", "10000", "11111"},
        {"11111", "00100", "00100", "00100", "00100"},
        {"11110", "10001", "11110", "10100", "10011"},
        {"11111", "00100", "00100", "00100", "11111"},
        {"01111", "10000", "01110", "00001", "11110"},
    };

    private enum Screen { MENU, CONTROLS, GAME }

    private final Color[][] board = new Color[ROWS][COLS];
    private final Random random = new Random();
    private Timer timer;
    private static final int FLASH_FRAMES = 100;

    private static final class Particle {
        float x, y, vx, vy, size;
        int life, maxLife;
        Color color;
    }

    private final List<Particle> particles = new ArrayList<>();
    private final List<int[]> flashes = new ArrayList<>();
    private Timer effectTimer;

    private static final int TRAIL_FRAMES = 14;

    private static final class Trail {
        int col, life;
        float yTop, yBottom;
        Color color;
    }

    private final List<Trail> trails = new ArrayList<>();
    private float fallProgress;
    private float animX, animY;
    private long lastFrameNanos;

    private static final class Star {
        float x, y, speed, size, phase;
    }

    private static final class Ghost {
        float x, y, speed;
        int type;
        Color color;
    }

    private static final int GHOST_CELL = 36;
    private final Star[] stars = new Star[90];
    private final Ghost[] ghosts = new Ghost[7];
    private float bgPulse;
    private float bgHue = 0.62f;

    private static final int SFX_MOVE = 0, SFX_ROTATE = 1, SFX_LOCK = 2, SFX_DROP = 3,
            SFX_CLEAR = 4,
            SFX_LEVEL = 8, SFX_OVER = 9, SFX_MENU = 10, SFX_SELECT = 11;
    private static final float RATE = 22050f;
    private static final AudioFormat FORMAT = new AudioFormat(RATE, 16, 1, true, false);
    private static final Random NOISE = new Random(7);
    private static final String BGM_FILE = "music.wav";
    private static final String MENU_MUSIC_FILE = "menu.wav";

    private Clip backgroundMusic;
    private Clip menuMusic;
    private final ExecutorService audioExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "audio");
        t.setDaemon(true);
        return t;
    });
    private byte[][] sounds;
    private volatile boolean audioFailed;
    private boolean soundOn = true;
    private boolean leveledUp;
    private long lastMoveSfx;


    private Screen screen = Screen.MENU;
    private int menuIndex;
    private boolean started;

    private int[][] current;
    private int currentType;
    private int nextType;
    private Color currentColor;
    private Color nextColor;
    private int px;
    private int py;
    private int score;
    private static final Path HIGH_SCORE_FILE = Path.of("highscore.txt");
    private int highScore;
    private boolean newHighScore;
    private int lines;
    private int level;
    private boolean gameOver;
    private boolean paused;
    private boolean confirmingRestart;

    public Tetris() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(Color.BLACK);
        setFocusable(true);
    }

    private void initialize() {
        addKeyListener(this);
        timer = new Timer(500, this);
        effectTimer = new Timer(16, ev -> updateEffects());
        effectTimer.start();
        initBackground();
        loadHighScore();
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveHighScore));
        buildSounds();
        loadBackgroundMusic();
        loadMenuMusic();
        screen = Screen.MENU;
        menuIndex = 0;
        updateBackgroundMusic();
    }
    private void loadHighScore() {
        try {
            if (Files.isRegularFile(HIGH_SCORE_FILE)) {
                highScore = Integer.parseInt(Files.readString(HIGH_SCORE_FILE).trim());
            }
        } catch (IOException | NumberFormatException ex) {
            highScore = 0;
        }
    }

    private void saveHighScore() {
        try {
            Files.writeString(HIGH_SCORE_FILE, String.valueOf(highScore));
        } catch (IOException ex) {
            System.err.println("Could not save high score: " + ex.getMessage());
        }
    }

    private Color randomColor() {
        return Color.getHSBColor(random.nextFloat(),
                0.6f + random.nextFloat() * 0.4f,
                0.8f + random.nextFloat() * 0.2f);
    }

    private void startGame() {
        for (int y = 0; y < ROWS; y++) {
            Arrays.fill(board[y], null);
        }
        score = 0;
        newHighScore = false;
        lines = 0;
        level = 1;
        gameOver = false;
        paused = false;
        confirmingRestart = false;
        started = true;
        particles.clear();
        flashes.clear();
        trails.clear();
        screen = Screen.GAME;
        nextType = random.nextInt(SHAPES.length);
        nextColor = randomColor();
        spawnPiece();
        timer.setDelay(delayForLevel());
        timer.restart();
        updateBackgroundMusic();
        repaint();
    }

    private int delayForLevel() {
        return Math.max(100, 500 - (level - 1) * 40);
    }

    private void spawnPiece() {
        currentType = nextType;
        currentColor = nextColor;
        nextType = random.nextInt(SHAPES.length);
        nextColor = randomColor();
        current = copyShape(SHAPES[currentType]);
        px = COLS / 2 - 1;
        py = 0;
        fallProgress = 0f;
        animX = 0f;
        animY = 0f;
        if (!isValid(current, px, py)) {
            gameOver = true;
            timer.stop();
            saveHighScore();
            updateBackgroundMusic();
            play(SFX_OVER);
        }
    }

    private int[][] copyShape(int[][] src) {
        int[][] copy = new int[4][2];
        for (int i = 0; i < 4; i++) {
            copy[i][0] = src[i][0];
            copy[i][1] = src[i][1];
        }
        return copy;
    }

    private boolean isValid(int[][] cells, int nx, int ny) {
        for (int[] c : cells) {
            int x = nx + c[0];
            int y = ny + c[1];
            if (x < 0 || x >= COLS || y >= ROWS) return false;
            if (y >= 0 && board[y][x] != null) return false;
        }
        return true;
    }

    private boolean tryMove(int dx, int dy) {
        if (isValid(current, px + dx, py + dy)) {
            px += dx;
            py += dy;
            return true;
        }
        return false;
    }

    private void rotate() {
        if (currentType == O_INDEX) return;
        int[][] rotated = new int[4][2];
        for (int i = 0; i < 4; i++) {
            rotated[i][0] = -current[i][1];
            rotated[i][1] = current[i][0];
        }
        int[] kicks = {0, -1, 1, -2, 2};
        for (int k : kicks) {
            if (isValid(rotated, px + k, py)) {
                current = rotated;
                px += k;
                animX -= k;
                play(SFX_ROTATE);
                return;
            }
        }
    }

    private void lockPiece(boolean hard) {
        for (int[] c : current) {
            int x = px + c[0];
            int y = py + c[1];
            if (y >= 0 && y < ROWS && x >= 0 && x < COLS) {
                board[y][x] = currentColor;
            }
        }
        int n = clearLines();
        if (hard) play(SFX_DROP);
        else if (n == 0) play(SFX_LOCK);
        if (n > 0) play(leveledUp ? SFX_LEVEL : SFX_CLEAR + Math.min(n, 4) - 1);
        spawnPiece();
    }

    private int clearLines() {
        for (int y = 0; y < ROWS; y++) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) {
                if (board[y][x] == null) {
                    full = false;
                    break;
                }
            }
            if (full) {
                for (int x = 0; x < COLS; x++) spawnSplash(y, x, board[y][x]);
                flashes.add(new int[] {y, FLASH_FRAMES});
            }
        }

        int cleared = 0;
        leveledUp = false;
        for (int y = ROWS - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < COLS; x++) {
                if (board[y][x] == null) {
                    full = false;
                    break;
                }
            }
            if (full) {
                cleared++;
                for (int row = y; row > 0; row--) {
                    System.arraycopy(board[row - 1], 0, board[row], 0, COLS);
                }
                Arrays.fill(board[0], null);
                y++;
            }
        }
        if (cleared > 0) {
            int[] points = {0, 100, 300, 500, 800};
            score += points[cleared] * level;
            lines += cleared;
            int oldLevel = level;
            level = lines / 10 + 1;
            leveledUp = level > oldLevel;
            bgPulse = 1f;
            timer.setDelay(delayForLevel());
        }
        return cleared;
    }

    private void hardDrop() {
        int startY = py;
        int dropped = 0;
        while (tryMove(0, 1)) dropped++;
        if (dropped > 0) addDropTrail(startY, py);
        score += dropped * 2;
        lockPiece(true);
    }

    private int ghostY() {
        int gy = py;
        while (isValid(current, px, gy + 1)) gy++;
        return gy;
    }
    private boolean gameInProgress() {
        return started && !gameOver;
    }

    private String[] menuItems() {
        String snd = "Sound: " + (soundOn ? "ON" : "OFF");
        if (gameInProgress()) {
            return new String[] {"Resume", "New Game", "Controls", snd, "Quit"};
        }
        return new String[] {"Start Game", "Controls", snd, "Quit"};
    }

    private void openMenu() {
        saveHighScore();
        screen = Screen.MENU;
        menuIndex = 0;
        timer.stop();
        updateBackgroundMusic();
        repaint();
    }

    private void activateMenuItem() {
        String item = menuItems()[menuIndex];
        if (item.startsWith("Sound")) {
            soundOn = !soundOn;
            play(SFX_SELECT);
            updateBackgroundMusic();
            repaint();
            return;
        }
        play(SFX_SELECT);
        switch (item) {
            case "Resume" -> {
                screen = Screen.GAME;
                if (!gameOver) timer.start();
            }
            case "Start Game", "New Game" -> startGame();
            case "Controls" -> screen = Screen.CONTROLS;
            case "Quit" -> System.exit(0);
            default -> { }
        }
        updateBackgroundMusic();
        repaint();
    }

    private void handleMenuKey(int key) {
        int n = menuItems().length;
        switch (key) {
            case KeyEvent.VK_UP -> {
                menuIndex = (menuIndex + n - 1) % n;
                play(SFX_MENU);
            }
            case KeyEvent.VK_DOWN -> {
                menuIndex = (menuIndex + 1) % n;
                play(SFX_MENU);
            }
            case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> {
                activateMenuItem();
                return;
            }
            case KeyEvent.VK_ESCAPE -> {
                if (gameInProgress()) {
                    menuIndex = 0;
                    activateMenuItem();
                    return;
                }
            }
            default -> { }
        }
        repaint();
    }
    @Override
    public void actionPerformed(ActionEvent e) {
        repaint();
    }
    private void initBackground() {
        for (int i = 0; i < stars.length; i++) {
            Star st = new Star();
            st.x = random.nextFloat() * WIDTH;
            st.y = random.nextFloat() * HEIGHT;
            st.speed = 0.2f + random.nextFloat() * 1.2f;
            st.size = st.speed > 0.9f ? 3 : st.speed > 0.5f ? 2 : 1;
            st.phase = random.nextFloat() * 6.28f;
            stars[i] = st;
        }
        for (int i = 0; i < ghosts.length; i++) {
            ghosts[i] = new Ghost();
            resetGhost(ghosts[i], true);
        }
    }

    private void resetGhost(Ghost gh, boolean anywhere) {
        gh.type = random.nextInt(SHAPES.length);
        gh.x = random.nextFloat() * (WIDTH - 5 * GHOST_CELL);
        gh.y = anywhere ? random.nextFloat() * HEIGHT : -4 * GHOST_CELL;
        gh.speed = 0.25f + random.nextFloat() * 0.6f;
        gh.color = randomColor();
    }

    private void updateBackground() {
        for (Star st : stars) {
            st.y += st.speed;
            st.phase += 0.06f;
            if (st.y > HEIGHT) {
                st.y = -2;
                st.x = random.nextFloat() * WIDTH;
            }
        }
        for (Ghost gh : ghosts) {
            gh.y += gh.speed;
            if (gh.y > HEIGHT + GHOST_CELL) resetGhost(gh, false);
        }
        bgPulse = Math.max(0f, bgPulse - 0.03f);

        if (screen == Screen.GAME) {
            float target = 0.62f + (level - 1) * 0.09f;
            float diff = ((target - bgHue + 1.5f) % 1f + 1f) % 1f - 0.5f;
            bgHue += diff * 0.02f;
        } else {
            bgHue += 0.0006f;
        }
        bgHue = ((bgHue % 1f) + 1f) % 1f;
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private void drawBackground(Graphics2D g) {
        Color top = Color.getHSBColor(bgHue, 0.7f, 0.04f + 0.10f * bgPulse);
        Color bottom = Color.getHSBColor(bgHue, 0.8f, 0.22f + 0.25f * bgPulse);
        g.setPaint(new GradientPaint(0, 0, top, 0, HEIGHT, bottom));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        for (Ghost gh : ghosts) {
            for (int[] c : SHAPES[gh.type]) {
                int rx = Math.round(gh.x + (c[0] + 1) * GHOST_CELL);
                int ry = Math.round(gh.y + (c[1] + 1) * GHOST_CELL);
                g.setColor(new Color(gh.color.getRed(), gh.color.getGreen(), gh.color.getBlue(), 30));
                g.fillRect(rx, ry, GHOST_CELL - 2, GHOST_CELL - 2);
                g.setColor(new Color(gh.color.getRed(), gh.color.getGreen(), gh.color.getBlue(), 60));
                g.setStroke(new BasicStroke(1f));
                g.drawRect(rx, ry, GHOST_CELL - 3, GHOST_CELL - 3);
            }
        }
        for (Star st : stars) {
            float twinkle = 0.5f + 0.5f * (float) Math.sin(st.phase);
            int alpha = clamp255((int) (70 + 160 * twinkle * (0.35f + st.size / 4f)));
            g.setColor(new Color(255, 255, 255, alpha));
            int sz = (int) st.size;
            g.fillRect(Math.round(st.x), Math.round(st.y), sz, sz);
        }
    }
    private static double[] voice(double f0, double f1, int ms, int wave, double vol) {
        int n = (int) (RATE * ms / 1000);
        double[] out = new double[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            phase += 2 * Math.PI * (f0 + (f1 - f0) * t) / RATE;
            double v = switch (wave) {
                case 0 -> Math.signum(Math.sin(phase));
                case 1 -> Math.sin(phase);
                case 2 -> NOISE.nextDouble() * 2 - 1;
                default -> 2 / Math.PI * Math.asin(Math.sin(phase));
            };
            double env = Math.min(1.0, i / (RATE * 0.004)) * Math.pow(1 - t, 1.5);
            out[i] = v * env * vol;
        }
        return out;
    }

    private static double[] cat(double[]... parts) {
        int len = 0;
        for (double[] p : parts) len += p.length;
        double[] out = new double[len];
        int pos = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static double[] mix(double[] a, double[] b) {
        double[] out = new double[Math.max(a.length, b.length)];
        for (int i = 0; i < a.length; i++) out[i] += a[i];
        for (int i = 0; i < b.length; i++) out[i] += b[i];
        return out;
    }

    private static byte[] pcm(double[] samples) {
        byte[] b = new byte[samples.length * 2];
        for (int i = 0; i < samples.length; i++) {
            short v = (short) (Math.max(-1.0, Math.min(1.0, samples[i])) * 32767);
            b[2 * i] = (byte) (v & 0xFF);
            b[2 * i + 1] = (byte) ((v >> 8) & 0xFF);
        }
        return b;
    }

    private static double[] clearSound(int lineCount) {
        double[] freqs = {523, 659, 784, 1047, 1319};
        double[][] notes = new double[lineCount + 1][];
        for (int i = 0; i <= lineCount; i++) {
            boolean last = (i == lineCount);
            double[] note = voice(freqs[i], freqs[i], last ? 220 : 65, last ? 3 : 0, 0.22);
            notes[i] = last ? mix(note, voice(freqs[i] * 2, freqs[i] * 2, 220, 1, 0.1)) : note;
        }
        return cat(notes);
    }

    private void buildSounds() {
        sounds = new byte[12][];
        sounds[SFX_MOVE] = pcm(voice(520, 480, 35, 0, 0.15));
        sounds[SFX_ROTATE] = pcm(voice(600, 900, 50, 0, 0.18));
        sounds[SFX_LOCK] = pcm(mix(voice(150, 60, 110, 3, 0.5), voice(0, 0, 50, 2, 0.12)));
        sounds[SFX_DROP] = pcm(mix(voice(220, 50, 170, 3, 0.6), voice(0, 0, 90, 2, 0.25)));
        for (int n = 1; n <= 4; n++) sounds[SFX_CLEAR + n - 1] = pcm(clearSound(n));
        sounds[SFX_LEVEL] = pcm(cat(voice(660, 660, 80, 0, 0.2), voice(880, 880, 80, 0, 0.2),
                voice(1320, 1320, 80, 0, 0.2), voice(1760, 1760, 260, 1, 0.3)));
        sounds[SFX_OVER] = pcm(cat(voice(400, 300, 180, 3, 0.4), voice(300, 200, 180, 3, 0.4),
                voice(200, 90, 450, 3, 0.45)));
        sounds[SFX_MENU] = pcm(voice(700, 700, 25, 1, 0.2));
        sounds[SFX_SELECT] = pcm(cat(voice(600, 600, 50, 0, 0.16), voice(900, 900, 90, 0, 0.16)));
    }

    private void loadBackgroundMusic() {
        backgroundMusic = loadMusicClip(BGM_FILE);
    }

    private void loadMenuMusic() {
        menuMusic = loadMusicClip(MENU_MUSIC_FILE);
    }

    private Clip loadMusicClip(String fileName) {
        File musicFile = new File(fileName);
        if (!musicFile.isFile()) return null;
        try (AudioInputStream stream = AudioSystem.getAudioInputStream(musicFile)) {
            Clip clip = AudioSystem.getClip();
            clip.open(stream);
            return clip;
        } catch (IOException | UnsupportedAudioFileException | LineUnavailableException ex) {
            System.err.println("Could not load " + fileName + ": " + ex.getMessage());
            return null;
        }
    }

    private void updateBackgroundMusic() {
        boolean playGameMusic = soundOn && screen == Screen.GAME
                && !paused && !gameOver && !confirmingRestart;
        boolean playMenuMusic = soundOn && screen == Screen.MENU;
        updateMusicClip(backgroundMusic, playGameMusic);
        updateMusicClip(menuMusic, playMenuMusic);
    }

    private void updateMusicClip(Clip clip, boolean shouldPlay) {
        if (clip == null) return;

        if (shouldPlay) {
            if (!clip.isRunning()) clip.loop(Clip.LOOP_CONTINUOUSLY);
        } else if (clip.isRunning()) {
            clip.stop();
        }
    }

    private void play(int id) {
        if (!soundOn || sounds == null || audioFailed) return;
        byte[] data = sounds[id];
        audioExec.execute(() -> {
            try {
                Clip clip = AudioSystem.getClip();
                clip.open(FORMAT, data, 0, data.length);
                clip.addLineListener(ev -> {
                    if (ev.getType() == LineEvent.Type.STOP) clip.close();
                });
                clip.start();
            } catch (Exception | LinkageError ex) {
                audioFailed = true;
            }
        });
    }

    private void moveSfx(boolean moved) {
        long now = System.currentTimeMillis();
        if (moved && now - lastMoveSfx > 50) {
            lastMoveSfx = now;
            play(SFX_MOVE);
        }
    }
    private void stepGravity(float dtMillis) {
        fallProgress += dtMillis / delayForLevel();
        if (fallProgress >= 1f) {
            fallProgress -= 1f;
            if (!tryMove(0, 1)) {
                lockPiece(false);
            }
        }
    }

    private void shift(int dx) {
        boolean moved = tryMove(dx, 0);
        if (moved) animX -= dx;
        moveSfx(moved);
    }

    private void addDropTrail(int startY, int endY) {
        int[] minRel = new int[COLS];
        Arrays.fill(minRel, Integer.MAX_VALUE);
        for (int[] c : current) {
            int col = px + c[0];
            if (col >= 0 && col < COLS) minRel[col] = Math.min(minRel[col], c[1]);
        }
        for (int col = 0; col < COLS; col++) {
            if (minRel[col] == Integer.MAX_VALUE) continue;
            Trail t = new Trail();
            t.col = col;
            t.yTop = Math.max(0, (startY + minRel[col]) * CELL);
            t.yBottom = (endY + minRel[col]) * CELL;
            t.life = TRAIL_FRAMES;
            t.color = currentColor;
            if (t.yBottom > t.yTop) trails.add(t);
        }
    }
    private void spawnSplash(int row, int col, Color color) {
        for (int k = 0; k < 8; k++) {
            Particle p = new Particle();
            p.x = col * CELL + CELL / 2f + (random.nextFloat() - 0.5f) * CELL * 0.6f;
            p.y = row * CELL + CELL / 2f;
            double angle = random.nextDouble() * Math.PI * 2;
            float speed = 1.5f + random.nextFloat() * 4f;
            p.vx = (float) Math.cos(angle) * speed;
            p.vy = (float) Math.sin(angle) * speed - 2f;
            p.size = 3f + random.nextFloat() * 5f;
            p.maxLife = 25 + random.nextInt(20);
            p.color = random.nextFloat() < 0.25f ? Color.WHITE : color;
            particles.add(p);
        }
    }

    private void updateEffects() {
        if (score > highScore) {
            highScore = score;
            newHighScore = true;
        }

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 16f : Math.min(50f, (now - lastFrameNanos) / 1e6f);
        lastFrameNanos = now;

        float decay = (float) Math.pow(0.5, dt / 40.0);
        animX *= decay;
        animY *= decay;
        if (Math.abs(animX) < 0.01f) animX = 0f;
        if (Math.abs(animY) < 0.01f) animY = 0f;

        if (screen == Screen.GAME && !gameOver && !paused && !confirmingRestart) {
            stepGravity(dt);
        }
        for (int i = trails.size() - 1; i >= 0; i--) {
            if (--trails.get(i).life <= 0) trails.remove(i);
        }
        updateBackground();
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.x += p.vx;
            p.y += p.vy;
            p.vy += 0.25f;     // gravity
            p.vx *= 0.98f;
            if (++p.life >= p.maxLife) particles.remove(i);
        }
        for (int i = flashes.size() - 1; i >= 0; i--) {
            if (--flashes.get(i)[1] <= 0) flashes.remove(i);
        }
        repaint();
    }

    private void drawEffects(Graphics2D g) {
        for (Trail t : trails) {
            float f = (float) t.life / TRAIL_FRAMES;
            Color clear = new Color(t.color.getRed(), t.color.getGreen(), t.color.getBlue(), 0);
            Color strong = new Color(t.color.getRed(), t.color.getGreen(), t.color.getBlue(),
                    clamp255((int) (170 * f)));
            g.setPaint(new GradientPaint(0, t.yTop, clear, 0, t.yBottom, strong));
            g.fillRect(t.col * CELL + 4, Math.round(t.yTop), CELL - 8,
                    Math.round(t.yBottom - t.yTop));
        }
        for (int[] f : flashes) {
            int alpha = (int) (200f * f[1] / FLASH_FRAMES);
            g.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, alpha))));
            g.fillRect(0, f[0] * CELL, COLS * CELL, CELL);
        }
        for (Particle p : particles) {
            float t = 1f - (float) p.life / p.maxLife;
            Color c = new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(),
                    Math.max(0, Math.min(255, (int) (255 * t))));
            int size = Math.max(1, Math.round(p.size * (0.4f + 0.6f * t)));
            g.setColor(c);
            g.fillRect(Math.round(p.x - size / 2f), Math.round(p.y - size / 2f), size, size);
        }
    }
    private void drawFilledCell(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.fillRect(x + 1, y + 1, CELL - 2, CELL - 2);
        g.setColor(color.darker());
        g.setStroke(new BasicStroke(1f));
        g.drawRect(x + 1, y + 1, CELL - 3, CELL - 3);
    }

    private void drawOutlineCell(Graphics2D g, int x, int y, Color color) {
        g.setColor(color);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x + 2, y + 2, CELL - 4, CELL - 4);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        drawBackground(g);

        if (screen == Screen.MENU) {
            drawMenu(g);
            return;
        }
        if (screen == Screen.CONTROLS) {
            drawControls(g);
            return;
        }
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(0, 0, COLS * CELL, ROWS * CELL);

        // grid
        g.setStroke(new BasicStroke(1f));
        g.setColor(GRID);
        for (int x = 0; x <= COLS; x++) g.drawLine(x * CELL, 0, x * CELL, ROWS * CELL);
        for (int y = 0; y <= ROWS; y++) g.drawLine(0, y * CELL, COLS * CELL, y * CELL);

        for (int y = 0; y < ROWS; y++) {
            for (int x = 0; x < COLS; x++) {
                if (board[y][x] != null) drawFilledCell(g, x * CELL, y * CELL, board[y][x]);
            }
        }

        if (!gameOver) {
            int gy = ghostY();
            for (int[] c : current) {
                int y = gy + c[1];
                if (y >= 0) drawOutlineCell(g, (px + c[0]) * CELL, y * CELL, GHOST);
            }
            float fy = isValid(current, px, py + 1) ? fallProgress : 0f;
            for (int[] c : current) {
                float cy = py + c[1] + fy + animY;
                if (cy > -1f) {
                    drawFilledCell(g, Math.round((px + c[0] + animX) * CELL),
                            Math.round(cy * CELL), currentColor);
                }
            }
        }

        drawEffects(g);

        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(2f));
        g.drawLine(COLS * CELL, 0, COLS * CELL, ROWS * CELL);

        drawSidePanel(g);

        if (confirmingRestart) {
            drawRestartDialog(g);
        } else if (gameOver) {
            drawCenteredOverlay(g, "GAME OVER", "R: restart   M: menu");
            g.setFont(new Font("Monospaced", Font.BOLD, 16));
            g.setColor(newHighScore ? Color.YELLOW : Color.WHITE);
            drawCentered(g, newHighScore ? "NEW HIGH SCORE: " + score : "SCORE: " + score,
                    COLS * CELL / 2, ROWS * CELL / 2 + 55);
        } else if (paused) {
            drawCenteredOverlay(g, "PAUSED", "P: resume   M: menu");
        }
    }

    private void drawCentered(Graphics2D g, String text, int centerX, int y) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, centerX - fm.stringWidth(text) / 2, y);
    }
    private void drawBlock(Graphics2D g, int x, int y, int size, Color color) {
        g.setColor(color);
        g.fillRect(x + 1, y + 1, size - 2, size - 2);
        g.setColor(color.brighter());
        g.fillRect(x + 1, y + 1, size - 2, 2);
        g.fillRect(x + 1, y + 1, 2, size - 2);
        g.setColor(color.darker().darker());
        g.fillRect(x + 1, y + size - 3, size - 2, 2);
        g.fillRect(x + size - 3, y + 1, 2, size - 2);
    }

    private void drawMenu(Graphics2D g) {
        int cx = WIDTH / 2;

        Color[] titleColors = {
            new Color(0, 240, 240), new Color(240, 160, 0), new Color(180, 0, 240),
            new Color(0, 240, 0), new Color(240, 0, 0), new Color(0, 100, 240)
        };
        int tc = 12;                       // title cell size
        int letterW = 5 * tc, letterGap = tc;
        int totalW = TITLE.length * letterW + (TITLE.length - 1) * letterGap;
        int tx = cx - totalW / 2;
        int ty = 80;
        for (int i = 0; i < TITLE.length; i++) {
            for (int row = 0; row < 5; row++) {
                for (int col = 0; col < 5; col++) {
                    if (TITLE[i][row].charAt(col) == '1') {
                        drawBlock(g, tx + col * tc, ty + row * tc, tc, titleColors[i]);
                    }
                }
            }
            tx += letterW + letterGap;
        }

        g.setFont(new Font("Monospaced", Font.BOLD, 18));
        g.setColor(Color.YELLOW);
        drawCentered(g, "HIGH SCORE: " + highScore, cx, 185);

        String[] items = menuItems();
        g.setFont(new Font("Monospaced", Font.BOLD, 22));
        int bw = 240, bh = 44, gap = 16;
        int top = 220;
        for (int i = 0; i < items.length; i++) {
            int by = top + i * (bh + gap);
            int bx = cx - bw / 2;
            boolean selected = (i == menuIndex);
            if (selected) {
                g.setColor(new Color(0, 60, 60));
                g.fillRect(bx, by, bw, bh);
            }
            g.setColor(selected ? OUTLINE : new Color(70, 70, 70));
            g.setStroke(new BasicStroke(2f));
            g.drawRect(bx, by, bw, bh);
            g.setColor(selected ? Color.WHITE : Color.GRAY);
            FontMetrics f2 = g.getFontMetrics();
            String label = selected ? "> " + items[i] + " <" : items[i];
            g.drawString(label, cx - f2.stringWidth(label) / 2,
                    by + (bh + f2.getAscent() - f2.getDescent()) / 2);
        }

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(Color.GRAY);
        drawCentered(g, "UP/DOWN: select    ENTER: confirm", cx, HEIGHT - 24);
    }

    private void drawControls(Graphics2D g) {
        int cx = WIDTH / 2;

        g.setColor(OUTLINE);
        g.setFont(new Font("Monospaced", Font.BOLD, 32));
        drawCentered(g, "CONTROLS", cx, 90);

        String[][] rows = {
            {"Left / Right", "Move"},
            {"Up", "Rotate"},
            {"Down", "Soft drop"},
            {"Space", "Hard drop"},
            {"P", "Pause"},
            {"R", "Restart"},
            {"M / Esc", "Back to menu"},
            {"S", "Sound on/off"}
        };
        g.setFont(new Font("Monospaced", Font.BOLD, 18));
        int y = 160;
        for (String[] row : rows) {
            g.setColor(Color.WHITE);
            g.drawString(row[0], cx - 190, y);
            g.setColor(Color.GRAY);
            g.drawString(row[1], cx + 20, y);
            y += 40;
        }

        g.setFont(new Font("Monospaced", Font.PLAIN, 14));
        g.setColor(Color.GRAY);
        drawCentered(g, "Press any key to go back", cx, HEIGHT - 30);
    }

    private void drawSidePanel(Graphics2D g) {
        int left = COLS * CELL + 20;

        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 16));
        g.drawString("NEXT", left, 30);

        int previewCell = 24;
        int[][] next = SHAPES[nextType];
        int baseX = left + 30;
        int baseY = 60;
        for (int[] c : next) {
            int cx = baseX + c[0] * previewCell;
            int cy = baseY + c[1] * previewCell;
            g.setColor(nextColor);
            g.fillRect(cx, cy, previewCell - 2, previewCell - 2);
            g.setColor(nextColor.darker());
            g.setStroke(new BasicStroke(1f));
            g.drawRect(cx, cy, previewCell - 3, previewCell - 3);
        }

        g.setColor(Color.WHITE);
        g.drawString("SCORE", left, 160);
        g.drawString(String.valueOf(score), left, 182);
        g.setColor(newHighScore ? Color.YELLOW : Color.WHITE);
        g.drawString("HIGH SCORE", left, 220);
        g.drawString(String.valueOf(highScore), left, 242);
        g.setColor(Color.WHITE);
        g.drawString("LINES", left, 280);
        g.drawString(String.valueOf(lines), left, 302);
        g.drawString("LEVEL", left, 340);
        g.drawString(String.valueOf(level), left, 362);

        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString("MUSIC", left, 392);
        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(Color.GRAY);
        g.drawString("music.wav (optional)", left, 410);

        g.setFont(new Font("Monospaced", Font.PLAIN, 12));
        g.setColor(Color.GRAY);
        String[] help = {
            "Left/Right : move",
            "Up         : rotate",
            "Down       : soft drop",
            "Space      : hard drop",
            "P          : pause",
            "R          : restart",
            "M          : menu",
            "S          : sound"
        };
        int hy = 444;
        for (String line : help) {
            g.drawString(line, left - 10, hy);
            hy += 18;
        }
    }

    private void drawRestartDialog(Graphics2D g) {
        int boardW = COLS * CELL;
        int boardH = ROWS * CELL;

        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, boardW, boardH);

        int w = 240, h = 130;
        int x = (boardW - w) / 2;
        int y = (boardH - h) / 2;
        g.setColor(new Color(20, 20, 20));
        g.fillRect(x, y, w, h);
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, w, h);

        g.setFont(new Font("Monospaced", Font.BOLD, 24));
        FontMetrics fm = g.getFontMetrics();
        String title = "Restart?";
        g.drawString(title, x + (w - fm.stringWidth(title)) / 2, y + 40);

        int bw = 90, bh = 36, gap = 20;
        int bx1 = x + (w - (2 * bw + gap)) / 2;
        int bx2 = bx1 + bw + gap;
        int by = y + 70;
        drawButton(g, bx1, by, bw, bh, "Yes (R)");
        drawButton(g, bx2, by, bw, bh, "No (X)");
    }

    private void drawButton(Graphics2D g, int x, int y, int w, int h, String label) {
        g.setColor(OUTLINE);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, w, h);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.BOLD, 14));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(label, x + (w - fm.stringWidth(label)) / 2,
                y + (h + fm.getAscent() - fm.getDescent()) / 2);
    }

    private void drawCenteredOverlay(Graphics2D g, String title, String subtitle) {
        g.setColor(new Color(0, 0, 0, 180));
        g.fillRect(0, 0, COLS * CELL, ROWS * CELL);

        g.setColor(OUTLINE);
        g.setFont(new Font("Monospaced", Font.BOLD, 28));
        FontMetrics fm = g.getFontMetrics();
        g.drawString(title, (COLS * CELL - fm.stringWidth(title)) / 2, ROWS * CELL / 2 - 10);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Monospaced", Font.PLAIN, 14));
        fm = g.getFontMetrics();
        g.drawString(subtitle, (COLS * CELL - fm.stringWidth(subtitle)) / 2, ROWS * CELL / 2 + 20);
    }
    @Override
    public void keyPressed(KeyEvent e) {
        int key = e.getKeyCode();

        if (screen == Screen.MENU) {
            handleMenuKey(key);
            return;
        }
        if (screen == Screen.CONTROLS) {
            screen = Screen.MENU;
            updateBackgroundMusic();
            repaint();
            return;
        }
        if (confirmingRestart) {
            if (key == KeyEvent.VK_R) {
                startGame();
            } else if (key == KeyEvent.VK_X) {
                confirmingRestart = false;
                updateBackgroundMusic();
                repaint();
            }
            return;
        }

        if (key == KeyEvent.VK_M || key == KeyEvent.VK_ESCAPE) {
            play(SFX_SELECT);
            openMenu();
            return;
        }
        if (key == KeyEvent.VK_S) {
            soundOn = !soundOn;
            play(SFX_SELECT);
            updateBackgroundMusic();
            return;
        }

        if (key == KeyEvent.VK_R) {
            if (gameOver) {
                startGame();
            } else {
                confirmingRestart = true;
                updateBackgroundMusic();
                repaint();
            }
            return;
        }
        if (gameOver) return;

        if (key == KeyEvent.VK_P) {
            paused = !paused;
            play(SFX_SELECT);
            updateBackgroundMusic();
            repaint();
            return;
        }
        if (paused) return;

        switch (key) {
            case KeyEvent.VK_LEFT -> shift(-1);
            case KeyEvent.VK_RIGHT -> shift(1);
            case KeyEvent.VK_DOWN -> {
                if (tryMove(0, 1)) {
                    score += 1;
                    animY -= 1f;   // glide down instead of jumping a row
                }
            }
            case KeyEvent.VK_UP -> rotate();
            case KeyEvent.VK_SPACE -> hardDrop();
            default -> {
                return;
            }
        }
        repaint();
    }

    @Override
    public void keyReleased(KeyEvent e) { }

    @Override
    public void keyTyped(KeyEvent e) { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Tetris");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            Tetris game = new Tetris();
            frame.add(game);
            frame.pack();
            frame.setLocationRelativeTo(null);
            game.initialize();
            frame.setVisible(true);
            game.requestFocusInWindow();
        });
    }
} 