package kr.ac.hansung.remoteDesktop.connection.server;

import kr.ac.hansung.remoteDesktop.connection.ConnectionType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class AudioServer extends Server {

    public AudioServer(SessionManager sessionManager, int port) {
        super(sessionManager, port);
        connectionType = ConnectionType.AUDIO;
    }

    @Override
    public String getSessionID(Socket socket) {
        try {
            var bw = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            return bw.readLine();
        } catch (IOException e) {
            return null;
        }
    }
}