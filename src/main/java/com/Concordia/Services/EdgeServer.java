package com.Concordia.Services;

import com.Concordia.Core.Directory;
import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.*;

public class EdgeServer implements Directory {
    DatagramSocket udpSocket;
    boolean discoveryPhase = true;

    Map<String, String> peerLookUpTable = new HashMap<>();

    public void start(int port) throws IOException {
        udpSocket = new DatagramSocket(port);
        while (discoveryPhase) {
            new PeerHandler(udpSocket, peerLookUpTable, discoveryPhase).updateLookupTable();
            discoveryPhase = false;
        }
    }

    public void clientHandler(int port) throws IOException {
        ClientHandler clientHandler = new ClientHandler(peerLookUpTable, port);
        clientHandler.start();
    }


    public static void main(String[] args) throws IOException {
        int port = 6661;
        int tcpPort = 8000;
        EdgeServer server = new EdgeServer();
        System.out.println("Server up and running");
        server.start(port);
        System.out.println("Discovery phase ended");
        System.out.println("waiting for client");
        while (true) {
            server.clientHandler(tcpPort);
        }
    }
}

class PeerHandler extends Thread {
    boolean discoverPhase;
    DatagramSocket socket;
    MulticastSocket multicastSocket;
    Map<String, String> lookup;
    InetAddress groupAddress = InetAddress.getByName("225.0.0.1");
    int groupPort = 5555;
    byte[] buffer = new byte[4096];
    int peerCount = 0;

    PeerHandler(DatagramSocket socket, Map<String, String> lookup, boolean discovery) throws IOException {
        this.socket = socket;
        this.lookup = lookup;
        this.multicastSocket = new MulticastSocket(5555);
        multicastSocket.joinGroup(groupAddress);
        multicastSocket.setReuseAddress(true);
        this.discoverPhase = discovery;
    }

    void updateLookupTable() {
        while (discoverPhase) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String packetMessage = new String(packet.getData(), packet.getOffset(), packet.getLength());
                InetAddress address = packet.getAddress();
                int peerPort = packet.getPort();
                if (packetMessage.equals("PEER_DISC")) {
                    String peerAdd = address.getHostAddress() + " " + peerPort;
                    if (!lookup.containsValue(peerAdd)) {
                        peerCount++;
                        lookup.put("r" + peerCount, peerAdd);
                        //No of peers allowed in the network. After 3 Edge server stops listening for other peers.
                        if (peerCount == 3) {
                            discoverPhase = false;
                        }
                    }
                }
                ObjectMapper mapper = new ObjectMapper();
                String jsonLookUp = "";
                try {
                    jsonLookUp = mapper.writeValueAsString(lookup);

                } catch (JsonGenerationException ex) {
                    System.out.println("Exception while converting lookup to json string");
                    ex.printStackTrace();
                }
                buffer = jsonLookUp.getBytes();
                DatagramPacket respToPeerPacket = new DatagramPacket(buffer, buffer.length, groupAddress, groupPort);
//                socket.send(respToPeerPacket);
                multicastSocket.send(respToPeerPacket);
                System.out.println("-----------");
                System.out.println("New Peer Added: " + address + " " + peerPort);
                System.out.println("Look table sent to all peers");
                lookup.forEach((key, value) -> System.out.println(key + " = " + value));
                if (!discoverPhase) {
                    buffer = "DISC_END".getBytes();
                    DatagramPacket endDiscPacket = new DatagramPacket(buffer, buffer.length, groupAddress, groupPort);
                    multicastSocket.send(endDiscPacket);
                }

            } catch (IOException ex) {
                System.out.println("Something happened during discovery phase ");
                ex.printStackTrace();
            }
        }
    }

}

class ClientHandler {
    Map<String, String> lookupTable;
    ServerSocket tcpSocket;
    Socket socket;
    PrintWriter out;
    BufferedReader in;
    HashMap<String, ArrayList<Integer>> map = new HashMap<>();

    ClientHandler(Map<String, String> lookupTable, int port) throws IOException {
        this.lookupTable = lookupTable;
        tcpSocket = new ServerSocket(port);
    }

    void start() throws IOException {
        try {
            while (true) {
                socket = tcpSocket.accept();
                System.out.println("A client just connected");
                out = new PrintWriter(socket.getOutputStream(), true);
                in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String inputLine = "";
                out.println("You are now connected to the server");
                int sum;
                while ((inputLine = in.readLine()) != null || !(inputLine = in.readLine()).equals("exit")) {
                    if (!inputLine.contains(" ")) {
                        out.println("Server : Please Enter the correct command");
                        continue;
                    }
                    String[] vectors = inputLine.split(" ");
                    if (vectors[1].contains(".")) {
                        String peerResp = handleForwardReq(vectors);
                        System.out.println("Peer Response: " + peerResp);
                        out.println(peerResp);
                        continue;
                    }
                    ArrayList<Integer> arrayList = map.get(vectors[1]);
                    switch (vectors[0]) {
                        case "SET":
                            ArrayList<Integer> newArrayList = new ArrayList<>();
                            newArrayList.add(Integer.parseInt(vectors[2]));
                            map.put(vectors[1], newArrayList);
                            out.println("Server : OK. Key set : "+vectors[1]);
                            System.out.println("Data set for key : " + vectors[1]);
                            break;
                        case "ADD":
                            arrayList.add(Integer.parseInt(vectors[2]));
                            map.put(vectors[1], arrayList);
                            out.println("Server : OK. List is : "+arrayList);
                            System.out.println("Data added for key : " + vectors[1]);
                            break;
                        case "SUM":
                            sum = arrayList.stream().mapToInt(Integer::intValue).sum();
                            out.println("Server : OK. Sum for the key: " + sum);
                            System.out.println("Data summed for key : " + vectors[1]);
                            break;
                        case "DELETE":
                            arrayList.remove(new Integer(vectors[2]));
                            out.println("Server : OK " + arrayList);
                            System.out.println("Data deleted for key : " + vectors[1]);
                            break;
                        case "DSUM":
                            sum = arrayList.stream().mapToInt(Integer::intValue).sum();
                            int peerSum = handleDSUM(vectors);
                            if (Arrays.asList(vectors).contains("INCLUDING")) {
                                peerSum += sum;
                            }
//                            out.println("Server : OK ");
                            out.println("Server: OK. Aggregate SUM = " + peerSum);
                            System.out.println("Data Aggregate Sum " + peerSum);
                            break;
                        case "DELETE_KEY":
                            map.remove(vectors[1]);
                            out.println("Data for Key "+vectors[1]+" Deleted: "+arrayList);
                            System.out.println("Key "+vectors[1]+" and corresponding data deleted "+arrayList);
                            break;
                        case"RESET":
                            if(!Objects.equals(vectors[1], "r0")){
                                out.println(handlePeerReset(vectors));
                                break;
                            }
                            map.clear();
                            System.out.println("Request to clear repo executed");
                            break;
                        case "LIST_KEYS":
                            if(vectors[1].contains("r")){
                                out.println(handlePeerKeyList(vectors[1]));
                                break;
                            }
                            ArrayList<String> keyList=new ArrayList<>(map.keySet());
                            out.println("Key List : "+keyList);
                            break;
                        case"LIST_VALUES":
                            if(vectors[1].contains("r")){
                                out.println(handlePeerValsList(vectors[1]));
                                break;
                            }
                            ArrayList<Integer> valueList=new ArrayList<>(map.get(vectors[1]));
                            out.println("Values associated with Key "+vectors[1]+ " : "+valueList);
                            break;
                        case "GET_VALUE":
                            if(vectors[1].contains("r")){
                                out.println(handlePeerRandValList(vectors[1]));
                                break;
                            }
                            int randIndex=(int)(Math.random() * map.get(vectors[1]).size());
                            out.println("A value associated with the Key "+vectors[1]+ " : "+map.get(vectors[1]).get(randIndex));
                            break;
                        default:
                            out.println("Server: Error. Please Enter the correct command");
                    }
                }

                socket.close();
                out.close();
            }

        } catch (Exception e) {
            out.println("Error Occurred Shutting down");
            System.out.println("An Exception Occurred");
        }
    }

    String handleForwardReq(String[] vectors) throws IOException {
        String[] peerInfo = vectors[1].split("\\.");
        String peer = peerInfo[0];
        String dictionaryKey = peerInfo[1];
        String peerIP = lookupTable.get(peer).split(" ")[0];
        int peerPort = Integer.parseInt(lookupTable.get(peer).split(" ")[1]);
        socket = new Socket(peerIP, peerPort);
        PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String message = "";
        if (vectors.length > 2) {
            message = vectors[0] + " " + dictionaryKey + " " + vectors[2];
        } else {
            message = vectors[0] + " " + dictionaryKey;
        }
        peerOut.println(message);
        //        System.out.println(resp);
        return peerIn.readLine();
    }

    int handleDSUM(String[] vectors) throws IOException {
        int sum = 0;
        if (vectors.length > 3) {
            for (int i = 2; i < vectors.length; i++) {
                if (vectors[i].equals("INCLUDING")) continue;
                String peer = vectors[i];
                String peerIP = lookupTable.get(peer).split(" ")[0];
                int peerPort = Integer.parseInt(lookupTable.get(peer).split(" ")[1]);
                socket = new Socket(peerIP, peerPort);
                PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String msg = "SUM " + vectors[1];
                peerOut.println(msg);
                sum += Integer.parseInt(peerIn.readLine().split(" ")[3]);
            }
        }
        return sum;
    }

    boolean handlePeerReset(String[] vectors) throws IOException {
        boolean flag=false;
        if(vectors.length>2){
            for(int i=1;i<vectors.length;i++){
                String peer = vectors[i];
                String peerIP = lookupTable.get(peer).split(" ")[0];
                int peerPort = Integer.parseInt(lookupTable.get(peer).split(" ")[1]);
                socket = new Socket(peerIP, peerPort);
                PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
                BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String message="RESET";
                peerOut.println(message);
                String resp=peerIn.readLine();
                System.out.println("Reset Request Sent to Peer "+peer+" at port "+peerPort );
                flag= Objects.equals(resp, "Repo reset");
                System.out.println("Peer Response: "+resp);
            }
        }
        return flag;
    }
    String handlePeerKeyList(String repo) throws IOException {
        String peerIP = lookupTable.get(repo).split(" ")[0];
        int peerPort = Integer.parseInt(lookupTable.get(repo).split(" ")[1]);
        socket = new Socket(peerIP, peerPort);
        PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String message="LIST_KEYS";
        peerOut.println(message);
        String resp=peerIn.readLine();
        System.out.println("Peer Response: "+resp);
        return resp;
    }
    String handlePeerValsList(String repo) throws IOException {
        String peerIP = lookupTable.get(repo).split(" ")[0];
        int peerPort = Integer.parseInt(lookupTable.get(repo).split(" ")[1]);
        socket = new Socket(peerIP, peerPort);
        PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String message="LIST_VALS";
        peerOut.println(message);
        String resp=peerIn.readLine();
        System.out.println("Peer Response: "+resp);
        return resp;
    }

    String handlePeerRandValList(String repo) throws IOException {
        String peerIP = lookupTable.get(repo).split(" ")[0];
        int peerPort = Integer.parseInt(lookupTable.get(repo).split(" ")[1]);
        socket = new Socket(peerIP, peerPort);
        PrintWriter peerOut = new PrintWriter(socket.getOutputStream(), true);
        BufferedReader peerIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        String message="LIST_VAL";
        peerOut.println(message);
        String resp=peerIn.readLine();
        System.out.println("Peer Response: "+resp);
        return resp;
    }

}
