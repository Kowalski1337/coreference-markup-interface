package server;

import chain.Action;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerArray;

public class JudgeStore {
    class Game {
        int teamOneId;
        int teamTwoId;
        int textNum;

        List<Action> teamOneApproved;
        List<Action> teamTwoApproved;
        List<Integer> decisions;

        PrintWriter writer;

        Game(int teamOneId, int teamTwoId, int textNum, String prefix) {
            this.teamOneId = teamOneId;
            this.teamTwoId = teamTwoId;

            teamOneApproved = new CopyOnWriteArrayList<>();
            teamTwoApproved = new CopyOnWriteArrayList<>();
            decisions = new CopyOnWriteArrayList<>();

            this.textNum = textNum;

            try {
                writer = new PrintWriter(prefix + ServerImpl.DELIMETER + teamOneId + "vs" + teamTwoId + "text=" + textNum);
                dumpWriter.println(teamOneId + "vs" + teamTwoId + "text=" + textNum);
                dumpWriter.flush();
            } catch (FileNotFoundException e) {
                System.err.println("Can't find file: " + teamOneId + "vs" + teamTwoId + "text=" + textNum);
            }
        }

        Game(int teamOneId, int teamTwoId, int textNum, List<Action> teamOneApproved, List<Action> teamTwoApproved, List<Integer> decisions, PrintWriter writer) {
            this.teamOneId = teamOneId;
            this.teamTwoId = teamTwoId;
            this.textNum = textNum;
            this.teamOneApproved = teamOneApproved;
            this.teamTwoApproved = teamTwoApproved;
            this.decisions = decisions;
            this.writer = writer;
        }
    }

    List<Game> games;
    PrintWriter dumpWriter;

    JudgeStore() {
        games = new CopyOnWriteArrayList<>();
    }

    public void setJudgeWriter(String prefix) {
        try {
            dumpWriter = new PrintWriter(prefix + ServerImpl.DELIMETER + "judgeStoreGames");
        } catch (FileNotFoundException e) {
            System.err.println("Can't find file " + prefix + ServerImpl.DELIMETER + "judgeStoreGames");
        }
    }

    public void putOneAction(Action teamOne, Action teamTwo, int textNum, int decision) {
        //System.out.println(textNum + " " + decision);
        games.get(textNum).teamOneApproved.add(teamOne);
        games.get(textNum).teamTwoApproved.add(teamTwo);
        games.get(textNum).decisions.add(decision);

        PrintWriter writer = games.get(textNum).writer;
        writer.println(teamOne.pack() + "@" + teamTwo.pack() + "@" + decision);
        writer.flush();
    }

    public List<Action> getTeamList(int textNum, int teamNum) {
        if (teamNum == 1) {
            return games.get(textNum).teamOneApproved;
        } else {
            return games.get(textNum).teamTwoApproved;
        }
    }

    public List<Integer> getDecisionList(int textNum) {
        return games.get(textNum).decisions;
    }

    public void addNewGame(int teamOneId, int teamTwoId, int textNum, String prefix) {
        Game tmp = new Game(teamOneId, teamTwoId, textNum, prefix);
        games.add(tmp);
    }

    public void addNewRecoverGame(int teamOneId, int teamTwoId, int textNum, List<Action> teamOneApproved, List<Action> teamTwoApproved, List<Integer> decisions, PrintWriter writer) {
        Game tmp = new Game(teamOneId, teamTwoId, textNum, teamOneApproved, teamTwoApproved, decisions, writer);
        games.add(tmp);
    }
}