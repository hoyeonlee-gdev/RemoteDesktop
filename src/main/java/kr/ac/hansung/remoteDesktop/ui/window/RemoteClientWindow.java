package kr.ac.hansung.remoteDesktop.ui.window;

import kr.ac.hansung.remoteDesktop.client.connection.ClientSession;
import kr.ac.hansung.remoteDesktop.exception.ConnectionFailureException;
import kr.ac.hansung.remoteDesktop.ui.component.RemoteScreen;
import kr.ac.hansung.remoteDesktop.ui.window.dialog.AskPasswordDialog;
import kr.ac.hansung.remoteDesktop.ui.window.event.ClientFileDropHandlerImpl;
import kr.ac.hansung.remoteDesktop.ui.window.event.ClientWindowListener;
import kr.ac.hansung.remoteDesktop.ui.window.event.FileDropHandler;
import kr.ac.hansung.remoteDesktop.ui.window.event.FileDropListener;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.dnd.DropTarget;
import java.awt.event.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class RemoteClientWindow implements IRDPWindow, Runnable {
    private JFrame clientWindow;
    private final String  address;
    byte[]          buffer = null;
    private final RemoteScreen remoteScreen;
    FileDropHandler fileDropHandler;
    private       boolean shouldStop;

    public RemoteClientWindow(String title, String address) {
        remoteScreen = createWindow(title);
        this.address = address;
        shouldStop   = false;

        buffer = new byte[1920 * 1080 * 4];
    }

    public void setTitle(String title) {
        if (clientWindow != null) {
            clientWindow.setTitle(title);
        }
    }

    private RemoteScreen createWindow(String title) {
        final RemoteScreen remoteScreen;
        clientWindow = new JFrame(title);
        clientWindow.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        clientWindow.setLayout(new BorderLayout());
        clientWindow.setSize(1920, 1080);
        clientWindow.setLocationRelativeTo(null);
        remoteScreen = new RemoteScreen(1920, 1080);
        clientWindow.add(remoteScreen, BorderLayout.CENTER);
        var clientWindowListener = new ClientWindowListener();
        clientWindow.addWindowListener(clientWindowListener);

        clientWindow.setVisible(true);

        clientWindow.addWindowStateListener(e -> {
            if (e.getNewState() == WindowEvent.WINDOW_CLOSING) shouldStop = true;
        });
        return remoteScreen;
    }

    public void showClient() {
        clientWindow.setVisible(true);
    }

    public void hideClient() {
        clientWindow.setVisible(false);
    }

    @Override
    public void stopWindowAndService() {
        shouldStop = true;
    }

    @Override
    public void add(Component component) {

    }

    public void add(Component component, Object constraints) {
        clientWindow.add(component, constraints);
    }

    public byte[] getBuffer() {
        return buffer;
    }

    public void updateRemoteScreen() throws IOException {
        remoteScreen.setImage(ImageIO.read(new ByteArrayInputStream(getBuffer())));
        remoteScreen.repaint();
    }

    public String askPassword(AskPasswordDialog.Type type) {
        var askPasswordWindow = new AskPasswordDialog(type, clientWindow);
        askPasswordWindow.setPosition(100, 100);
        askPasswordWindow.setDialogSize(400, 300);
        AtomicReference<String> password = new AtomicReference<>("");
        askPasswordWindow.setSubmit(l -> {
            password.set(askPasswordWindow.getPassword());
            askPasswordWindow.dispose();
        });
        askPasswordWindow.setCancel(r -> {
            askPasswordWindow.dispose();
        });
        askPasswordWindow.start();
        return password.get();
    }

    private ClientSession createClientSession() throws ConnectionFailureException {
        var clientSession = ClientSession.Factory.createClientSession(address, this::askPassword);
        var receiver      = clientSession.getMessageReceiver();
        var mouseSender   = clientSession.getMouseSender();
        var keySender     = clientSession.getKeySender();

        clientWindow.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                try {
                    super.keyPressed(e);
                    keySender.sendKeyPress(e.getKeyCode());
                } catch (IOException ex) {
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                try {
                    super.keyReleased(e);
                    keySender.sendKeyRelease(e.getKeyCode());
                } catch (IOException ex) {
                }
            }
        });

        clientWindow.getContentPane().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int     button  = e.getButton();
                boolean pressed = true;
                try {
                    mouseSender.sendMouseClick(button, pressed);
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });

        clientWindow.getContentPane().addMouseMotionListener(new MouseAdapter() {
            private long lastSent = System.nanoTime();

            @Override
            public void mouseMoved(MouseEvent e) {
                super.mouseMoved(e);
                try {
                    long now = System.nanoTime();
                    if ((now - lastSent) / 1000_000 > 30) {
                        mouseSender.sendMouseMove(e.getX(), e.getY());
                        System.out.printf("\r\n%d %d", e.getX(), e.getY());
                        lastSent = now;
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }
        });

        receiver.setOnWindowCloseReceived(() -> clientWindow.dispose());
        receiver.setOnMouseMessageReceived(r -> {
            remoteScreen.setMouseX(r.getX());
            remoteScreen.setMouseY(r.getY());
            remoteScreen.repaint();
        });

        var fileDropListener = new FileDropListener();
        fileDropHandler = new ClientFileDropHandlerImpl(clientSession);
        fileDropListener.addFileDropAction(fileDropHandler);
        new DropTarget(clientWindow, fileDropListener);

        System.out.println("연결했습니다.");
        return clientSession;
    }

    @Override
    public void run() {
        try (ClientSession clientSession = createClientSession()) {

            var receiver = clientSession.getMessageReceiver();

            // 서버로부터의 메시지를 처리하는 스레드
            new Thread(() -> {
                while (true) {
                    try {
                        receiver.handleServerMessage();
                    } catch (IOException | ClassNotFoundException e) {
                        clientWindow.dispose();
                        break;
                    }
                }
            }).start();

            showClient();

            try {
                while (!clientSession.isClosed()) {
                    var videoReceiver = clientSession.getVideoReceiver();
                    int len           = videoReceiver.receiveVideo(getBuffer());
                    int STOP          = -100;
                    if (len == STOP) break;
                    updateRemoteScreen();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
