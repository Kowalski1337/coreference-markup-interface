package server;

import chain.Action;
import chain.Chain;
import document.ConflictInfo;
import document.UpdateDocument;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.stream.Collectors;

/**
 * This class describes interaction protocol of Server
 *
 * @author Vadim Baydyuk
 * @see Server
 */
public class ServerImpl implements Server {
    /**
     * Identifier of server.
     */
    private int port;

    /**
     * An internal endpoint for sending or receiving data.
     */
    private ServerSocket socket;

    /**
     * Texts.
     */
    private List<String> texts;

    /**
     * Pool of clients.
     */
    private Queue<Socket> clients;

    /**
     * Pool of users.
     */
    private Queue<Socket> users;

    /**
     * Pool of users.
     */
    private Queue<Socket> onlineUsers;

    /**
     * Pool of users.
     */
    private Queue<Socket> offlineUsers;

    /**
     * Set of judges id.
     */
    private Set<Integer> judgesId;

    /**
     * Pool of judges.
     */
    private Set<JudgeInfo> judges;

    /**
     * List of workers
     */
    private List<Thread> workers;

    /**
     * List of conflicts
     */
    static Queue<ConflictInfo> conflicts;

    private Queue<AddTask> tasks;

    private ServerStore serverStore;
    private JudgeStore judgeStore;


    Map<Integer, Socket> idToSocket;

    Map<Socket, Integer> socketToId;

    AtomicBoolean work;

    PrintWriter logWriter;

    public ServerImpl(int port) {
        this.port = port;
        try {
            socket = new ServerSocket(port);
        } catch (IOException e) {
            e.printStackTrace();
        }
        work = new AtomicBoolean(true);
        texts = new CopyOnWriteArrayList<>();

        users = new ConcurrentLinkedQueue<>();
        judges = new CopyOnWriteArraySet<>();
        clients = new ConcurrentLinkedQueue<>();
        offlineUsers = new ConcurrentLinkedQueue<>();
        onlineUsers = new ConcurrentLinkedQueue<>();
        tasks = new ConcurrentLinkedQueue<>();
        workers = new CopyOnWriteArrayList<>();

        judgesId = new ConcurrentSkipListSet<>();

        serverStore = new ServerStore();
        judgeStore = new JudgeStore();


        idToSocket = new ConcurrentHashMap<>();
        socketToId = new ConcurrentHashMap<>();

        conflicts = new ConcurrentLinkedQueue<>();

        try {
            logWriter = new PrintWriter("server.log");
        } catch (FileNotFoundException e) {
            System.err.println("file server.log not found");
        }
    }

    public void loadTexts(List<String> filenames) {
        for (String filename : filenames) {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(new File(filename))));

                StringBuilder text = new StringBuilder();
                String help = "";

                while ((help = reader.readLine()) != null) {
                    text.append(help + " ");
                }

                texts.add(text.toString());
            } catch (FileNotFoundException e) {
                System.err.println("Can't find file : " + filename);
            } catch (IOException e) {
                System.err.println("Can't read file : " + filename + ". Error: " + e.getMessage());
            }
        }
    }

    Thread listenerThread;
    Thread schedulerThread;
    Thread userSchedulerThread;
    Thread onlineUsersSchedulerThread;
    Thread offlineUsersSchedulerThread;
    Thread serverStoreWorkerThread;
    Thread addTaskWorkerThread;
    Thread conflictInfoSchedulerThread;

    /**
     * Start server
     */
    public void run() {
        listenerThread = new Thread(listener);
        schedulerThread = new Thread(scheduler);
        userSchedulerThread = new Thread(userScheduler);
        onlineUsersSchedulerThread = new Thread(onlineUsersScheduler);
        offlineUsersSchedulerThread = new Thread(offlineUsersScheduler);
        serverStoreWorkerThread = new Thread(serverStore.worker);
        addTaskWorkerThread = new Thread(addTaskWorker);
        conflictInfoSchedulerThread = new Thread(conflictInfoScheduler);

        listenerThread.start();
        schedulerThread.start();
        userSchedulerThread.start();
        onlineUsersSchedulerThread.start();
        offlineUsersSchedulerThread.start();
        serverStoreWorkerThread.start();
        addTaskWorkerThread.start();
        conflictInfoSchedulerThread.start();
        try {
            listenerThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    public void close() {
        work.compareAndSet(true, false);

        try {
            listenerThread.join();
            schedulerThread.join();
            userSchedulerThread.join();
            onlineUsersSchedulerThread.join();
            offlineUsersSchedulerThread.join();
            serverStoreWorkerThread.join();
            addTaskWorkerThread.join();
            conflictInfoSchedulerThread.join();
        } catch (InterruptedException e) {
            System.err.println("close :=: Error: " + e.getMessage());
        }
    }

    /**
     * Connect clients to server
     */
    private Runnable listener = () -> {
        while (work.get()) {
            try {
                Socket client = socket.accept();
                System.out.println("listener :=: connected: " + client.toString());
                clients.offer(client);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("listener :=: ServerSocket closed with error: " + e.getMessage());
        }
    };

    /**
     * Split clients on clients or judges
     */
    private Runnable scheduler = () -> {
        while (work.get()) {
            try {

                if (!clients.isEmpty()) {
                    Socket client = clients.poll();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(client.getOutputStream())));

                        String request = reader.readLine();
                        System.out.println("scheduler :=: Request from " + client.toString() + " = " + request);

                        int id = Integer.parseInt(request);

                        idToSocket.put(id, client);
                        socketToId.put(client, id);

                        if (id > 100) {
                            judges.add(new JudgeInfo(client, judges.size()));
                        } else {
                            users.offer(client);
                        }

                        writer.println("OK");
                        writer.flush();
                    } catch (Exception e) {
                        System.err.println("scheduler :=: Error: " + e.getMessage());
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.err.println("scheduler :=: scheduler interrupted: " + e.getMessage());
            }

        }
    };

    /**
     * Split users on online or offline users
     */
    private Runnable userScheduler = () -> {
        while (work.get()) {
            try {

                if (!users.isEmpty()) {
                    Socket client = users.poll();
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(client.getOutputStream())));

                        String request = reader.readLine();
                        System.out.println("userScheduler :=: request from " + client.toString() + " = " + request);

                        int id = Integer.parseInt(request);
                        if (id == 0) {
                            onlineUsers.offer(client);
                        } else {
                            offlineUsers.offer(client);
                        }

                        writer.println("OK");
                        writer.flush();
                    } catch (Exception e) {
                        System.err.println("userScheduler :=: Error: " + e.getMessage());
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.err.println("userScheduler :=: userScheduler interrupted: " + e.getMessage());
            }
        }
    };

    /**
     * Give task for online users
     */
    private Runnable onlineUsersScheduler = () -> {
        AtomicInteger textNumber = new AtomicInteger();
        while (work.get()) {
            try {

                if (onlineUsers.size() >= 2) {
                    Socket client1 = onlineUsers.poll();
                    Socket client2 = onlineUsers.poll();
                    try {

                        int text = textNumber.get();

                        textNumber.getAndIncrement();

                        BufferedReader reader1 = new BufferedReader(new InputStreamReader(client1.getInputStream()));
                        BufferedReader reader2 = new BufferedReader(new InputStreamReader(client2.getInputStream()));

                        PrintWriter writer1 = new PrintWriter(new BufferedWriter(new OutputStreamWriter(client1.getOutputStream())));
                        PrintWriter writer2 = new PrintWriter(new BufferedWriter(new OutputStreamWriter(client2.getOutputStream())));

                        writer1.println(texts.get(text));
                        writer2.println(texts.get(text));

                        writer1.flush();
                        writer2.flush();

                        serverStore.addNewGame(socketToId.get(client1), socketToId.get(client2), text);
                        judgeStore.addNewGame(socketToId.get(client1), socketToId.get(client2), text);

                        Thread thread1 = new Thread(() -> {
                            while (true) {
                                try {
                                    String request = reader1.readLine();
                                    if (request == null) {
                                        break;
                                    }
                                    UpdateDocument document = new UpdateDocument(request);
                                    /*serverStore.putActions(document.getActions(), text, 1);*/
                                    tasks.add(new AddTask(document.getActions(), text, 1));
                                } catch (IOException e) {
                                    System.err.println("onlineUsersScheduler[client1, id = " + socketToId.get(client1) + "] :=: Error: " + e.getMessage());
                                }

                            }
                        });

                        Thread thread2 = new Thread(() -> {
                            while (true) {
                                try {
                                    String request = reader2.readLine();
                                    if (request == null) {
                                        break;
                                    }
                                    UpdateDocument document = new UpdateDocument(request);
                                    /*serverStore.putActions(document.getActions(), text, 1);*/
                                    tasks.add(new AddTask(document.getActions(), text, 2));
                                } catch (IOException e) {
                                    System.err.println("onlineUsersScheduler[client2, id = " + socketToId.get(client2) + "] :=: Error: " + e.getMessage());
                                }

                            }
                        });

                        thread1.start();
                        thread2.start();
                        workers.add(thread1);
                        workers.add(thread2);

                    } catch (IOException e) {
                        System.err.println("onlineUsersScheduler :=: Error: " + e.getMessage());
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.out.println("onlineUsersScheduler :=: onlineUsersScheduler interrupted : " + e.getMessage());
            }
        }
    };

    /**
     * Give task for offline users
     */
    private Runnable offlineUsersScheduler = () -> {
        int textNumber = 0;
        while (work.get()) {
            try {

                if (!offlineUsers.isEmpty()) {
                    Socket client = offlineUsers.poll();
                    System.out.println("offlineUsersScheduler :=: " + client.toString());
                    try {
                        BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(client.getOutputStream())));
                        int text = textNumber;

                        textNumber++;

                        if (textNumber > texts.size()) {
                            textNumber = 0;
                        }

                        writer.println(texts.get(text));
                        writer.flush();
                        Thread worker = new Thread(() -> {
                            try {
                                String doc = reader.readLine();
                                UpdateDocument document = new UpdateDocument(doc);
                                List<Action> answer = document.getActions();
                                //save data

                            } catch (IOException e) {
                                System.out.println("offlineUsersScheduler[worker] :=: Error: " + e.getMessage());
                            }
                        });
                        worker.run();
                        workers.add(worker);
                    } catch (IOException e) {
                        System.out.println("offlineUsersScheduler :=: Error: " + e.getMessage());
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.out.println("offlineUsersScheduler :=: offlineUsersScheduler interrupted : " + e.getMessage());
            }
        }
    };

    private Runnable addTaskWorker = () -> {
        while (work.get() || !tasks.isEmpty()) {
            try {
                if (!tasks.isEmpty()) {
                    AddTask addTask = tasks.poll();
                    if (!serverStore.putActions(addTask.getActionList(), addTask.getTextNum(), addTask.getTeamNum())) {
                        tasks.add(addTask);
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.out.println("addTaskWorker :=: addTaskWorker interrupted : " + e.getMessage());
            }
        }
    };

    private Runnable conflictInfoScheduler = () -> {
        while (!conflicts.isEmpty() || work.get()) {
            try {
                if (!conflicts.isEmpty()) {
                    ConflictInfo conflict = conflicts.poll();
                    if (conflict.status.get() == 0) {
                        boolean f = false;
                        List<JudgeInfo> judgeInfoList;
                        synchronized (judges) {
                             judgeInfoList = new ArrayList<>(judges);
                        }
                        /*logWriter.println(judgeInfoList.stream().map(judgeInfo -> socket.toString()).collect(Collectors.joining("----")));
                        logWriter.flush();*/
                        for (JudgeInfo judgeInfo: judgeInfoList) {
                            if (!judgeInfo.task.isMarked()) {
                                if (judgeInfo.setTask(conflict)) {
                                    /*logWriter.println("get task" + judges.get(j).socket + " " + conflict.textId + " " + conflict.teamOneId + " " + conflict.teamTwoId);
                                    logWriter.flush();*/
                                    f = true;
                                    break;
                                }
                            }
                        }
                        //System.out.println("not get " + conflict.textId + " " + conflict.teamOneId + " " + conflict.teamTwoId);
                        conflicts.add(conflict);
                    } else if (conflict.status.get() == 1) {
                        //System.out.println("in process " + conflict.textId + " " + conflict.teamOneId + " " + conflict.teamTwoId);
                        conflicts.add(conflict);
                    }
                } else {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                System.out.println("conflictInfoScheduler :=: conflictInfoScheduler interrupted : " + e.getMessage());
            }
        }
    };

    AtomicBoolean down = new AtomicBoolean(false);
    Random random = new Random();

    class JudgeInfo {
        Socket socket;
        int index;
        /**
         * reference has mark set false if judge is free otherwise true
         */
        AtomicMarkableReference<ConflictInfo> task;
        Thread worker;

        JudgeInfo(Socket socket, int index) {
            this.index = index;
            this.socket = socket;
            task = new AtomicMarkableReference<>(null, false);
            worker = new Thread(judgeWorker);
            worker.start();
        }

        boolean setTask(ConflictInfo task) {
            return this.task.compareAndSet(null, task, false, true);
        }

        Runnable judgeWorker = () -> {
            try {
                while (true) {
                    try {
                        if (task.isMarked()) {
                            if (task.getReference().apply()) {
                                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                                PrintWriter writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())));

                                ConflictInfo conflict = task.getReference();

                                List<Action> teamOneActions = judgeStore.getTeamList(conflict.textId, 1);
                                List<Action> teamTwoActions = judgeStore.getTeamList(conflict.textId, 2);
                                List<Integer> decisions = judgeStore.getDecisionList(conflict.textId);

                                Action action1 = conflict.action1;
                                Action action2 = conflict.action2;

                                List<Action> toJudgeAboutTeamOne = new CopyOnWriteArrayList<>();
                                List<Action> toJudgeAboutTeamTwo = new CopyOnWriteArrayList<>();

                                toJudgeAboutTeamOne.add(action1);
                                toJudgeAboutTeamTwo.add(action2);

                                if (!action1.isEmpty()) {
                                    for (int i = teamOneActions.size() - 1; i >= 0 && i >= teamOneActions.size() - 100 &&
                                            toJudgeAboutTeamOne.size() < 8; i--) {
                                        if (!teamOneActions.get(i).isEmpty() && (decisions.get(i) == 1 || decisions.get(i) == 3)) {
                                            toJudgeAboutTeamOne.add(0, teamOneActions.get(i));
                                        }
                                    }
                                }

                                if (!action2.isEmpty()) {
                                    for (int i = teamTwoActions.size() - 1; i >= 0 && i >= teamTwoActions.size() - 100 &&
                                            toJudgeAboutTeamTwo.size() < 8; i--) {
                                        if (!teamTwoActions.get(i).isEmpty() && (decisions.get(i) == 2 || decisions.get(i) == 3)) {
                                            toJudgeAboutTeamTwo.add(0, teamTwoActions.get(i));
                                        }
                                    }
                                }

                                /*if ((random.nextInt() % 3 == 0) && down.compareAndSet(false, true)) {
                                    System.out.println(socket.toString() + "down");
                                    socket.close();
                                }*/

                                /*logWriter.println("send task " + socket.toString());
                                logWriter.flush();*/

                                UpdateDocument teamOne = new UpdateDocument(toJudgeAboutTeamOne);
                                UpdateDocument teamTwo = new UpdateDocument(toJudgeAboutTeamTwo);

                                if (teamOne.pack() == null) {
                                    logWriter.println("null1" + socket.toString());
                                    logWriter.flush();
                                }

                                writer.println(teamOne.pack());
                                writer.flush();
                                /*Thread.sleep(1000);*/
                                if (teamTwo.pack() == null) {
                                    logWriter.println("null2" + socket.toString());
                                    logWriter.flush();
                                }

                                writer.println(teamTwo.pack());
                                writer.flush();


                                writer.println(conflict.textId);
                                writer.flush();

                                String request = reader.readLine();
                                if (request == null) {
                                    logWriter.println("done judge" + socket.toString());
                                    logWriter.flush();
                                    synchronized (judges) {
                                        socket.close();
                                        judges.remove(this);
                                    }
                                    break;
                                }
                            /*logWriter.println("get decision from judge" + socket.toString());
                            logWriter.flush();*/
                                int decision = Integer.parseInt(request);
                                judgeStore.putOneAction(action1, action2, conflict.textId, decision);

                                if (conflict.complete()) {
                                    logWriter.println("judge" + socket.toString() + " complete task");
                                    logWriter.flush();
                                }

                                task.compareAndSet(conflict, null, true, false);
                            }
                        } else {
                            Thread.sleep(1000);
                        }
                    } catch (IOException e) {
                        logWriter.println("done judge" + socket.toString());
                        logWriter.flush();
                        synchronized (judges) {
                            judges.remove(this);

                        }
                        System.err.println("judgeWorker :=: Error: " + e.getMessage());
                        break;
                    }
                }
            } catch (InterruptedException e) {
                System.err.println("judgeWorker" + socket.toString() + " :=: Error: " + e.getMessage());
            }
        };

    }


}
