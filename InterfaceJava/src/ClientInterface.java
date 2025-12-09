import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClientInterface extends JFrame {

    // --- Configuration ---
    private final int CELL_SIZE = 30; // Taille visuelle d'une case en pixels
    private final float FOV_RADIUS_UNITS = 1.0f; // Rayon FOV (2.5 unités de grille)
    private final String SERVER_HOST = "127.0.0.1";
    private final int SERVER_PORT = 5001;

    // --- Composants UI ---
    private CardLayout cardLayout;
    private JPanel mainContainer;
    private SimulationPanel simulationPanel;
    private JTable statsTable;
    private DefaultTableModel tableModel;
    private JSlider speedSlider;

    // --- Composants UI Stats Globales (CORRIGÉ) ---
    private JLabel lblMoyEnergie;
    private JLabel lblMoyStress;
    private JLabel lblMoyArgent;
    private JLabel lblNbVivants;
    private JLabel lblNbMorts;
    private JLabel lblNbOccupes;

    // --- Réseau ---
    private PrintWriter out;
    private Socket socket;
    private volatile boolean connected = false;

    // --- Données Simulation (Thread Safe) ---
    private int gridWidth = 35;
    private int gridHeight = 23;

    private List<AgentInfo> agents = new CopyOnWriteArrayList<>();
    private List<LieuInfo> lieux = new CopyOnWriteArrayList<>();

    public ClientInterface() {
        setTitle("Super Simulation Multi-Agent | Interface Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        resizeWindow(gridWidth, gridHeight);
        setLocationRelativeTo(null);

        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);

        mainContainer.add(createMenuPanel(), "MENU");
        mainContainer.add(createGameInterface(), "GAME");

        add(mainContainer);
        setVisible(true);

        new Thread(this::networkLoop).start();
    }

    private void resizeWindow(int w, int h) {
        int width = (w * CELL_SIZE) + 450;
        int height = (h * CELL_SIZE) + 150;
        width = Math.max(width, 1050);
        height = Math.max(height, 750);
        setSize(width, height);
        if (simulationPanel != null) {
            simulationPanel.setPreferredSize(new Dimension(w * CELL_SIZE, h * CELL_SIZE));
        }
        revalidate();
    }

    // ========================================================================
    // CONSTRUCTION DE L'INTERFACE (PANNEAUX)
    // ========================================================================

    private JPanel createMenuPanel() {
        // ... (Pas de changement) ...
        JPanel menu = new JPanel(new GridBagLayout());
        menu.setBackground(new Color(30, 30, 35));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15);
        gbc.gridx = 0; gbc.gridy = 0;

        JLabel title = new JLabel("SIMULATION VILLE INTELLIGENTE");
        title.setFont(new Font("Segoe UI", Font.BOLD, 32));
        title.setForeground(new Color(255, 105, 180));
        menu.add(title, gbc);

        gbc.gridy++;
        JLabel subTitle = new JLabel("Choisissez un scénario d'émergence :");
        subTitle.setForeground(Color.CYAN);
        subTitle.setFont(new Font("Arial", Font.PLAIN, 18));
        menu.add(subTitle, gbc);

        gbc.gridy++;
        menu.add(createStyledButton("S1: La Ruée vers l'Or (Argent/Travail)", e -> lancerScenario(1)), gbc);

        gbc.gridy++;
        menu.add(createStyledButton("S2: Panique & Densité (Stress/Foule)", e -> lancerScenario(2)), gbc);

        gbc.gridy++;
        menu.add(createStyledButton("S3: Survie Difficile (Ressources Rares)", e -> lancerScenario(3)), gbc);

        gbc.gridy++;
        JButton btnQuit = createStyledButton("Quitter", e -> System.exit(0));
        btnQuit.setBackground(new Color(225, 150, 55));
        menu.add(btnQuit, gbc);

        return menu;
    }

    private JPanel createGameInterface() {
        JPanel gamePanel = new JPanel(new BorderLayout());

        // 1. Zone Centrale (Carte)
        simulationPanel = new SimulationPanel();
        simulationPanel.setBackground(new Color(45, 45, 50));
        JScrollPane scrollSim = new JScrollPane(simulationPanel);
        scrollSim.setBorder(BorderFactory.createEmptyBorder());
        gamePanel.add(scrollSim, BorderLayout.CENTER);

        // 2. Zone Droite (Priorisation: Stats > Tableau > Légende)
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setPreferredSize(new Dimension(420, 0));
        rightPanel.setBackground(new Color(60, 60, 65));

        // a. Panneau Stats Globales (Haut)
        JPanel statsPanelContainer = new JPanel(new BorderLayout());
        statsPanelContainer.add(createGlobalStatsPanel(), BorderLayout.NORTH);
        statsPanelContainer.add(createRepartitionPanel(), BorderLayout.CENTER); // NOUVEAU panel Repartition
        statsPanelContainer.setMaximumSize(new Dimension(420, 200));
        rightPanel.add(statsPanelContainer);

        // b. Tableau Stats Agents (Milieu)
        String[] columnNames = {"Nom", "Énergie", "Stress", "$", "État"};
        tableModel = new DefaultTableModel(columnNames, 0);
        statsTable = new JTable(tableModel);
        statsTable.setBackground(new Color(50, 50, 55));
        statsTable.setForeground(Color.WHITE);
        statsTable.setFillsViewportHeight(true);
        statsTable.setGridColor(Color.DARK_GRAY);
        JScrollPane tableScroll = new JScrollPane(statsTable);

        tableScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 90)), "Agents Détaillés", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.LIGHT_GRAY));

        tableScroll.setPreferredSize(new Dimension(420, 400));
        rightPanel.add(tableScroll);

        // c. Légende (Bas)
        JScrollPane legendScroll = createLegendPanelScrollable();
        legendScroll.setPreferredSize(new Dimension(420, 150));
        legendScroll.setMaximumSize(new Dimension(420, 200));
        rightPanel.add(legendScroll);

        gamePanel.add(rightPanel, BorderLayout.EAST);

        // 3. Zone Bas (Contrôles)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        controlPanel.setBackground(new Color(50, 20, 100));

        JButton btnBack = new JButton("⬅ Retour Menu / Stop");
        btnBack.setBackground(new Color(200, 100, 100));
        btnBack.addActionListener(e -> retourMenu());

        speedSlider = new JSlider(1, 10, 2);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        speedSlider.setBackground(new Color(50, 20, 100));
        speedSlider.setForeground(Color.LIGHT_GRAY);
        speedSlider.addChangeListener(e -> envoyer("SPEED:" + speedSlider.getValue()));

        JLabel lblSpeed = new JLabel("Vitesse Simulation :");
        lblSpeed.setForeground(Color.WHITE);

        controlPanel.add(btnBack);
        controlPanel.add(lblSpeed);
        controlPanel.add(speedSlider);

        gamePanel.add(controlPanel, BorderLayout.SOUTH);

        return gamePanel;
    }

    private JPanel createGlobalStatsPanel() {
        JPanel stats = new JPanel(new GridLayout(3, 2, 5, 5));
        stats.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY), "Moyennes (Vivants)", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.CYAN));
        stats.setBackground(new Color(60, 60, 65));

        lblMoyEnergie = createStatLabel("N/A");
        lblMoyStress = createStatLabel("N/A");
        lblMoyArgent = createStatLabel("N/A");

        addStatRow(stats, "Énergie Moy:", lblMoyEnergie);
        addStatRow(stats, "Stress Moy:", lblMoyStress);
        addStatRow(stats, "Argent Moy:", lblMoyArgent);

        return stats;
    }

    // NOUVEAU PANEL POUR LA RÉPARTITION
    private JPanel createRepartitionPanel() {
        JPanel rep = new JPanel(new GridLayout(3, 2, 5, 5));
        rep.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY), "Répartition", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.CYAN));
        rep.setBackground(new Color(60, 60, 65));

        lblNbVivants = createStatLabel("N/A");
        lblNbMorts = createStatLabel("N/A");
        lblNbOccupes = createStatLabel("N/A");

        addStatRow(rep, "Agents Vivants:", lblNbVivants);
        addStatRow(rep, "Agents Morts:", lblNbMorts);
        addStatRow(rep, "Agents Occupés:", lblNbOccupes);

        return rep;
    }

    private JLabel createStatLabel(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Color.GREEN);
        l.setFont(new Font("Consolas", Font.BOLD, 14));
        return l;
    }

    private void addStatRow(JPanel p, String title, JLabel val) {
        JLabel t = new JLabel(title);
        t.setForeground(Color.WHITE);
        p.add(t);
        p.add(val);
    }

    private JScrollPane createLegendPanelScrollable() {
        JPanel legend = new JPanel();
        legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
        legend.setBackground(new Color(60, 60, 65));
        legend.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.GRAY), "Légendes", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.WHITE));

        legend.add(createLegendItem("🏠 Maison", new Color(70, 70, 90)));
        legend.add(createLegendItem("🏢 Travail", new Color(150, 80, 50)));
        legend.add(createLegendItem("🌳 Parc", new Color(50, 120, 70)));
        legend.add(createLegendItem("🍿 Loisir", new Color(130, 50, 130)));
        legend.add(Box.createVerticalStrut(10));

        legend.add(new JLabel("<html><font color='white'>--- État Agents ---</font></html>"));
        legend.add(createLegendItem("🟢 Sain", new Color(50, 200, 50)));
        legend.add(createLegendItem("🔴 Critique", Color.RED));
        legend.add(createLegendItem("⚪ Occupé (Gris)", Color.GRAY));
        legend.add(createLegendItem("⚫ Mort (Noir)", Color.BLACK));

        JScrollPane scroll = new JScrollPane(legend);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private JLabel createLegendItem(String text, Color color) {
        String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        JLabel l = new JLabel("<html><span style='color:"+hex+"; font-size:14px;'>■</span> " + text + "</html>");
        l.setForeground(Color.LIGHT_GRAY);
        l.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        return l;
    }

    private JButton createStyledButton(String text, java.awt.event.ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Segoe UI", Font.BOLD, 14));
        btn.setPreferredSize(new Dimension(300, 45));
        btn.setBackground(new Color(70, 130, 180));
        btn.setForeground(Color.DARK_GRAY);
        btn.setFocusPainted(false);
        btn.addActionListener(action);
        return btn;
    }

    // ========================================================================
    // LOGIQUE RÉSEAU
    // ========================================================================

    private void networkLoop() {
        while (true) {
            try {
                if (socket == null || socket.isClosed()) {
                    try {
                        socket = new Socket(SERVER_HOST, SERVER_PORT);
                        out = new PrintWriter(socket.getOutputStream(), true);
                        connected = true;
                        System.out.println("Connecté au serveur Python.");
                    } catch (IOException e) {
                        Thread.sleep(1000);
                        continue;
                    }
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;

                // Tampons pour la trame en cours
                List<AgentInfo> bufferAgents = new ArrayList<>();
                List<LieuInfo> bufferLieux = new ArrayList<>();
                String[] tempStats = null;

                while ((line = in.readLine()) != null) {
                    if (line.startsWith("CONFIG")) {
                        String[] parts = line.split(";");
                        if (parts.length >= 3) {
                            int w = Integer.parseInt(parts[1]);
                            int h = Integer.parseInt(parts[2]);
                            SwingUtilities.invokeLater(() -> {
                                this.gridWidth = w;
                                this.gridHeight = h;
                                resizeWindow(w, h);
                            });
                        }
                    }
                    else if (line.equals("END")) {
                        // Traitement de la fin de trame
                        final List<AgentInfo> finalAgents = new ArrayList<>(bufferAgents);
                        final List<LieuInfo> finalLieux = new ArrayList<>(bufferLieux);
                        final String[] finalStats = tempStats;

                        // Mise à jour des listes partagées (thread-safe)
                        this.agents = new CopyOnWriteArrayList<>(finalAgents);
                        this.lieux = new CopyOnWriteArrayList<>(finalLieux);

                        // Mise à jour de l'UI dans le thread de l'EDT
                        SwingUtilities.invokeLater(() -> {
                            if (mainContainer.isVisible()) {
                                simulationPanel.repaint(); // Redessin de la carte
                                updateTable(); // Mise à jour du tableau

                                // Mise à jour des labels stats (Format: STATS;MoyNrj;MoyStress;MoyArg;NbVivants;NbMorts;NbOccupes)
                                // Doit avoir au moins 7 champs [0] à [6]
                                if (finalStats != null && finalStats.length >= 7) {

                                    // Moyennes (Index 1 à 3)
                                    lblMoyEnergie.setText(String.format("%.1f", Double.parseDouble(finalStats[1])));
                                    lblMoyStress.setText(String.format("%.1f", Double.parseDouble(finalStats[2])));
                                    lblMoyArgent.setText(String.format("%.1f", Double.parseDouble(finalStats[3])));

                                    // Répartition (Index 4 à 6)
                                    lblNbVivants.setText(finalStats[4]);
                                    lblNbMorts.setText(finalStats[5]);
                                    lblNbOccupes.setText(finalStats[6]);
                                } else {
                                    // Réinitialisation si aucune stat valide n'est reçue
                                    lblMoyEnergie.setText("N/A");
                                    lblMoyStress.setText("N/A");
                                    lblMoyArgent.setText("N/A");
                                    lblNbVivants.setText("N/A");
                                    lblNbMorts.setText("N/A");
                                    lblNbOccupes.setText("N/A");
                                }
                            }
                        });

                        // Vider les tampons pour la prochaine trame
                        bufferAgents.clear();
                        bufferLieux.clear();
                        tempStats = null;

                    }
                    else {
                        // Traitement des lignes de données
                        String[] p = line.split(";");
                        String type = p[0];

                        if (type.equals("AGENT")) {
                            // AGENT;Nom;X;Y;Energie;Stress;Argent;Etat;Angle (9 champs total)
                            if (p.length == 9) {
                                bufferAgents.add(new AgentInfo(
                                        p[1],
                                        Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                                        Float.parseFloat(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]),
                                        p[7], Float.parseFloat(p[8])
                                ));
                            }
                        }
                        else if (type.equals("MAISON") || type.equals("TRAVAIL") || type.equals("PARC") || type.equals("LOISIR")) {
                            // LIEU;X;Y;OCCUPE (4 champs)
                            if (p.length >= 3) {
                                boolean occupe = p.length > 3 && p[3].equals("1");
                                bufferLieux.add(new LieuInfo(type, Float.parseFloat(p[1]), Float.parseFloat(p[2]), occupe));
                            }
                        }
                        else if (type.equals("STATS")) {
                            // STATS;MoyNrj;MoyStress;MoyArg;NbVivants;NbMorts;NbOccupes (7 champs total)
                            tempStats = p;
                        }
                    }
                }
            } catch (Exception e) {
                connected = false;
                socket = null;
                System.out.println("Déconnexion serveur... Tentative de reconnexion: " + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private void lancerScenario(int id) {
        lblMoyEnergie.setText("Calc...");
        lblMoyStress.setText("Calc...");
        lblMoyArgent.setText("Calc...");
        lblNbVivants.setText("...");
        lblNbMorts.setText("...");
        lblNbOccupes.setText("...");

        envoyer("SCENARIO:" + id);
        speedSlider.setValue(2);
        envoyer("SPEED:2");
        cardLayout.show(mainContainer, "GAME");
    }

    private void retourMenu() {
        envoyer("STOP");
        cardLayout.show(mainContainer, "MENU");
    }

    private void envoyer(String msg) {
        if (out != null) out.println(msg);
    }

    private void updateTable() {
        int newSize = agents.size();

        // Optimise le redimensionnement du tableau
        if (tableModel.getRowCount() != newSize) {
            tableModel.setRowCount(newSize);
        }

        for (int i = 0; i < newSize; i++) {
            AgentInfo a = agents.get(i);

            tableModel.setValueAt(a.nom, i, 0);
            tableModel.setValueAt((int)a.nrj, i, 1);
            tableModel.setValueAt((int)a.stress, i, 2);
            tableModel.setValueAt((int)a.arg, i, 3);
            tableModel.setValueAt(a.etat, i, 4);
        }
    }

    // ========================================================================
    // PANNEAU DE DESSIN
    // ========================================================================
    class SimulationPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 1. Dessine la grille de fond
            for (int x = 0; x < gridWidth; x++) {
                for (int y = 0; y < gridHeight; y++) {
                    g2.setColor(new Color(50, 50, 55));
                    g2.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                    g2.setColor(new Color(40, 40, 45));
                    g2.drawRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                }
            }

            if (!connected) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 20));
                g2.drawString("En attente du serveur Python...", 50, 50);
                return;
            }

            // 2. DESSIN LIEUX
            for (LieuInfo l : lieux) {
                int x = (int) (l.x * CELL_SIZE);
                int y = (int) (l.y * CELL_SIZE);
                if (l.x >= gridWidth || l.y >= gridHeight) continue;

                Color color = Color.GRAY;
                String emoji = "?";

                switch (l.type) {
                    case "MAISON" -> { color = new Color(70, 70, 90); emoji = "🏠"; }
                    case "TRAVAIL" -> { color = new Color(150, 80, 50); emoji = "🏢"; }
                    case "PARC" -> { color = new Color(50, 120, 70); emoji = "🌳"; }
                    case "LOISIR" -> { color = new Color(130, 50, 130); emoji = "🍿"; }
                }

                g2.setColor(color);
                g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                g2.setColor(color.darker());
                g2.drawRect(x, y, CELL_SIZE, CELL_SIZE);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
                g2.drawString(emoji, x + 2, y + 24);

                if (l.occupe) {
                    g2.setColor(Color.CYAN);
                    g2.fillOval(x + 22, y + 2, 6, 6);
                }
            }

            // 3. DESSIN AGENTS
            for (AgentInfo a : agents) {
                if (a.x >= gridWidth || a.y >= gridHeight) continue;
                int cx = (int) (a.x * CELL_SIZE) + CELL_SIZE / 2;
                int cy = (int) (a.y * CELL_SIZE) + CELL_SIZE / 2;

                // Dessin du FOV (Cone de vision)
                int fovRadiusPixels = (int) (FOV_RADIUS_UNITS * CELL_SIZE);
                int fovDiameterPixels = 2 * fovRadiusPixels;
                double deg = Math.toDegrees(-a.ang);
                double startAngle = deg - 30;
                double extentAngle = 60;

                g2.setColor(new Color(255, 255, 255, 40));
                g2.fill(new Arc2D.Double(
                        cx - fovRadiusPixels,
                        cy - fovRadiusPixels,
                        fovDiameterPixels,
                        fovDiameterPixels,
                        startAngle,
                        extentAngle,
                        Arc2D.PIE
                ));

                // DÉTERMINATION COULEUR AGENT
                Color bodyColor;
                if (a.etat.equals("Mort")) {
                    bodyColor = Color.BLACK;
                } else if (a.etat.equals("Occupé")) {
                    bodyColor = Color.GRAY; // AGENT OCCUPÉ EN GRIS
                } else {
                    // VIVANT / EN MOUVEMENT
                    bodyColor = (a.stress > 70 || a.nrj < 20) ? Color.RED : new Color(50, 200, 50);
                }

                // Dessin du corps
                g2.setColor(bodyColor);
                g2.fillOval(cx - 6, cy - 6, 12, 12);

                // Dessin du contour et du nom
                g2.setColor(Color.WHITE);
                g2.drawOval(cx - 6, cy - 6, 12, 12);
                g2.setFont(new Font("Arial", Font.PLAIN, 10));
                g2.drawString(a.nom, cx - 5, cy - 8);

                // Marqueur pour Mort
                if (a.etat.equals("Mort")) {
                    g2.setColor(Color.RED);
                    g2.setFont(new Font("Arial", Font.BOLD, 10));
                    g2.drawString("X", cx - 3, cy + 4);
                }
            }
        }
    }

    record AgentInfo(String nom, float x, float y, float nrj, float stress, float arg, String etat, float ang) {}
    record LieuInfo(String type, float x, float y, boolean occupe) {}

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ClientInterface());
    }
}