import java.awt.Canvas;
import java.awt.Color;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The game stand-in {@code RoundTripLiveTest} launches as a single-file program: a black, undecorated window of
 * {@code width}×{@code height} with one white {@code size}×{@code size} square at {@code (x, y)}. The first
 * press writes {@code "<x>,<y>"} — where it landed, in the window's own pixels — to {@code out} and exits.
 *
 * <p>Run with {@code -Dsun.java2d.uiScale=1}, so its pixels are the display's, as a game's are.
 *
 * <p>Usage: {@code java RoundTripTarget.java <width> <height> <x> <y> <size> <out>}
 */
public class RoundTripTarget {

    public static void main(String[] args) {
        int width = Integer.parseInt(args[0]);
        int height = Integer.parseInt(args[1]);
        int x = Integer.parseInt(args[2]);
        int y = Integer.parseInt(args[3]);
        int size = Integer.parseInt(args[4]);
        Path out = Path.of(args[5]);

        Frame frame = new Frame("BotMakerRoundTrip");
        frame.setUndecorated(true);
        Canvas canvas = new Canvas() {
            @Override
            public void paint(Graphics g) {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                g.fillRect(x, y, size, size);
            }
        };
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                try {
                    Files.writeString(out, e.getX() + "," + e.getY());
                } catch (Exception ignored) {
                    // the test times out and says nothing landed
                }
                System.exit(0);
            }
        });
        frame.add(canvas);
        frame.setBounds(0, 0, width, height);
        frame.setVisible(true);
    }
}
