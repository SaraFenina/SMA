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
    private final String SERVER_HOST = "127.0.0.1";
    private final int SERVER_PORT = 5001;

    // --- Composants UI ---
    private CardLayout cardLayout;
    private JPanel mainContainer;
    private SimulationPanel simulationPanel;
    private JTable statsTable;
    private DefaultTableModel tableModel;
    private JSlider speedSlider;

    // --- Réseau ---
    private PrintWriter out;
    private Socket socket;
    private volatile boolean connected = false;

    // --- Données Simulation (Thread Safe) ---
    private int gridWidth = 35;  // Valeur par défaut, sera mise à jour par le serveur
    private int gridHeight = 23;

    // On utilise des listes tampons pour éviter les scintillements
    private List<AgentInfo> agents = new CopyOnWriteArrayList<>();
    private List<LieuInfo> lieux = new CopyOnWriteArrayList<>();

    public ClientInterface() {
        setTitle("Super Simulation Multi-Agent | Interface Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Initialisation de la taille par défaut
        resizeWindow(gridWidth, gridHeight);
        setLocationRelativeTo(null);

        // Système de cartes pour basculer entre Menu et Jeu
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);

        mainContainer.add(createMenuPanel(), "MENU");
        mainContainer.add(createGameInterface(), "GAME");

        add(mainContainer);

        // Lancement du thread réseau (ne bloque pas l'interface)
        new Thread(this::networkLoop).start();
    }

    /**
     * Redimensionne la fenêtre en fonction de la taille de la grille reçue
     */
    private void resizeWindow(int w, int h) {
        // Largeur = Grille + Panneau Stats (400px) + Marges
        int width = (w * CELL_SIZE) + 420;
        // Hauteur = Grille + Panneau Contrôle (80px) + Marges
        int height = (h * CELL_SIZE) + 120;

        // Limites minimales pour que l'interface reste utilisable
        width = Math.max(width, 1000);
        height = Math.max(height, 700);

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
        JPanel menu = new JPanel(new GridBagLayout());
        menu.setBackground(new Color(30, 30, 35));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15);
        gbc.gridx = 0; gbc.gridy = 0;

        JLabel title = new JLabel("SIMULATION VILLE INTELLIGENTE");
        title.setFont(new Font("Segoe UI", Font.BOLD, 32));
        title.setForeground(new Color(255, 105, 180)); // Pink
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
        // ScrollPane au cas où la grille est géante
        JScrollPane scrollSim = new JScrollPane(simulationPanel);
        scrollSim.setBorder(BorderFactory.createEmptyBorder());
        gamePanel.add(scrollSim, BorderLayout.CENTER);

        // 2. Zone Droite (Tableau et Légende)
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.setPreferredSize(new Dimension(400, 0));
        rightPanel.setBackground(new Color(60, 60, 65));

        // Tableau Stats
        String[] columnNames = {"Nom", "Énergie", "Stress", "$", "État"};
        tableModel = new DefaultTableModel(columnNames, 0);
        statsTable = new JTable(tableModel);
        statsTable.setBackground(new Color(50, 50, 55));
        statsTable.setForeground(Color.WHITE);
        statsTable.setFillsViewportHeight(true);
        JScrollPane tableScroll = new JScrollPane(statsTable);
        tableScroll.setPreferredSize(new Dimension(400, 400));
        rightPanel.add(tableScroll, BorderLayout.NORTH);

        // Légende
        rightPanel.add(createLegendPanel(), BorderLayout.CENTER);
        gamePanel.add(rightPanel, BorderLayout.EAST);

        // 3. Zone Bas (Contrôles)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        controlPanel.setBackground(new Color(20, 20, 25));

        JButton btnBack = new JButton("⬅ Retour Menu / Stop");
        btnBack.setBackground(new Color(200, 100, 100));
        btnBack.addActionListener(e -> retourMenu());

        // Slider Vitesse
        speedSlider = new JSlider(1, 10, 2);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        speedSlider.setBackground(new Color(20, 20, 25));
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

    private JPanel createLegendPanel() {
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
        legend.add(createLegendItem("🟢 Sain (Energie > 20, Stress < 70)", new Color(50, 200, 50)));
        legend.add(createLegendItem("🔴 Critique (Fatigué ou Stressé)", Color.RED));

        return legend;
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
                // Tentative de connexion
                if (socket == null || socket.isClosed()) {
                    try {
                        socket = new Socket(SERVER_HOST, SERVER_PORT);
                        out = new PrintWriter(socket.getOutputStream(), true);
                        connected = true;
                        System.out.println("Connecté au serveur Python.");
                    } catch (IOException e) {
                        Thread.sleep(1000); // Réessaie toutes les secondes
                        continue;
                    }
                }

                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;

                // Buffers temporaires pour construire la frame
                List<AgentInfo> bufferAgents = new ArrayList<>();
                List<LieuInfo> bufferLieux = new ArrayList<>();

                while ((line = in.readLine()) != null) {
                    if (line.startsWith("CONFIG")) {
                        // Reception taille grille: CONFIG;W;H
                        String[] parts = line.split(";");
                        if (parts.length >= 3) {
                            int w = Integer.parseInt(parts[1]);
                            int h = Integer.parseInt(parts[2]);
                            // Mise à jour Thread-Safe de l'UI
                            SwingUtilities.invokeLater(() -> {
                                this.gridWidth = w;
                                this.gridHeight = h;
                                resizeWindow(w, h);
                            });
                        }
                    }
                    else if (line.equals("END")) {
                        // Fin de trame : on met à jour l'affichage
                        // On copie les buffers dans les listes principales
                        final List<AgentInfo> finalAgents = new ArrayList<>(bufferAgents);
                        final List<LieuInfo> finalLieux = new ArrayList<>(bufferLieux);

                        SwingUtilities.invokeLater(() -> {
                            this.agents = finalAgents;
                            this.lieux = finalLieux;
                            if (mainContainer.isVisible()) {
                                simulationPanel.repaint(); // Redessine la carte
                                updateTable();             // Met à jour le tableau
                            }
                        });

                        // Reset buffers
                        bufferAgents.clear();
                        bufferLieux.clear();
                    }
                    else {
                        // Parsing des données
                        parseLine(line, bufferAgents, bufferLieux);
                    }
                }
            } catch (Exception e) {
                connected = false;
                socket = null;
                System.out.println("Déconnexion serveur... Tentative de reconnexion.");
            }
        }
    }

    private void parseLine(String line, List<AgentInfo> bufA, List<LieuInfo> bufL) {
        try {
            String[] p = line.split(";");
            String type = p[0];

            if (type.equals("AGENT")) {
                // AGENT;Nom;X;Y;Energie;Stress;Argent;Etat;Angle
                bufA.add(new AgentInfo(
                        p[1],
                        Float.parseFloat(p[2]), Float.parseFloat(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]),
                        p[7], Float.parseFloat(p[8])
                ));
            }
            else if (type.equals("MAISON") || type.equals("TRAVAIL") || type.equals("PARC") || type.equals("LOISIR")) {
                // TYPE;X;Y;OCCUPE
                boolean occupe = p.length > 3 && p[3].equals("1");
                bufL.add(new LieuInfo(type, Float.parseFloat(p[1]), Float.parseFloat(p[2]), occupe));
            }
        } catch (Exception ignored) {}
    }

    private void lancerScenario(int id) {
        envoyer("SCENARIO:" + id);
        // Remettre slider par défaut
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
        // Optimisation: ne recréer pas tout le modèle si le nombre de lignes est le même
        if (tableModel.getRowCount() != agents.size()) {
            tableModel.setRowCount(0);
        }

        for (int i = 0; i < agents.size(); i++) {
            AgentInfo a = agents.get(i);
            if (tableModel.getRowCount() <= i) {
                tableModel.addRow(new Object[]{a.nom, (int)a.nrj, (int)a.stress, (int)a.arg, a.etat});
            } else {
                tableModel.setValueAt(a.nom, i, 0);
                tableModel.setValueAt((int)a.nrj, i, 1);
                tableModel.setValueAt((int)a.stress, i, 2);
                tableModel.setValueAt((int)a.arg, i, 3);
                tableModel.setValueAt(a.etat, i, 4);
            }
        }
    }

    // ========================================================================
    // PANNEAU DE DESSIN CUSTOMISÉ
    // ========================================================================
    class SimulationPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (!connected) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 20));
                g2.drawString("En attente du serveur Python...", 50, 50);
                return;
            }

            // 1. DESSIN DES LIEUX
            for (LieuInfo l : lieux) {
                // Conversion coordonnées grille -> pixels
                int x = (int) (l.x * CELL_SIZE);
                int y = (int) (l.y * CELL_SIZE);

                // Sécurité hors bornes
                if (l.x >= gridWidth || l.y >= gridHeight) continue;

                Color color = Color.GRAY;
                String emoji = "?";

                switch (l.type) {
                    case "MAISON" -> { color = new Color(70, 70, 90); emoji = "🏠"; }
                    case "TRAVAIL" -> { color = new Color(150, 80, 50); emoji = "🏢"; }
                    case "PARC" -> { color = new Color(50, 120, 70); emoji = "🌳"; }
                    case "LOISIR" -> { color = new Color(130, 50, 130); emoji = "🍿"; }
                }

                // Fond
                g2.setColor(color);
                g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);

                // Bordure légère
                g2.setColor(color.darker());
                g2.drawRect(x, y, CELL_SIZE, CELL_SIZE);

                // Emoji
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
                // Centrage approximatif
                g2.drawString(emoji, x + 2, y + 24);

                // Marqueur d'occupation (petit point cyan)
                if (l.occupe) {
                    g2.setColor(Color.CYAN);
                    g2.fillOval(x + 22, y + 2, 6, 6);
                }
            }

            // 2. DESSIN DES AGENTS
            for (AgentInfo a : agents) {
                if (a.x >= gridWidth || a.y >= gridHeight) continue;

                int cx = (int) (a.x * CELL_SIZE) + CELL_SIZE / 2;
                int cy = (int) (a.y * CELL_SIZE) + CELL_SIZE / 2;

                // Cône de vision (Direction)
                g2.setColor(new Color(255, 255, 255, 40));
                // Math.toDegrees car Arc2D attend des degrés
                // -angle car l'axe Y est inversé en écran (bas = positif) vs trigo classique
                double deg = Math.toDegrees(-a.ang);
                g2.fill(new Arc2D.Double(cx - 30, cy - 30, 60, 60, deg - 30, 60, Arc2D.PIE));

                // Corps de l'agent
                Color bodyColor = (a.stress > 70 || a.nrj < 20) ? Color.RED : new Color(50, 200, 50);
                g2.setColor(bodyColor);
                g2.fillOval(cx - 6, cy - 6, 12, 12);

                // Contour agent
                g2.setColor(Color.WHITE);
                g2.drawOval(cx - 6, cy - 6, 12, 12);

                // Nom de l'agent
                g2.setFont(new Font("Arial", Font.PLAIN, 10));
                g2.drawString(a.nom, cx - 5, cy - 8);
            }
        }
    }

    // Records pour stocker les données reçues
    record AgentInfo(String nom, float x, float y, float nrj, float stress, float arg, String etat, float ang) {}
    record LieuInfo(String type, float x, float y, boolean occupe) {}

    // Point d'entrée
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new ClientInterface().setVisible(true));
    }
}