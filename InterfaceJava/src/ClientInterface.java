/**
 *  Ecrit par Fenina Sara
 */

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.geom.Arc2D; // Utilisé pour le dessin du cône de vision (FOV)
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList; // Pour les listes thread-safe

/**
 * Classe principale de l'interface utilisateur. Gère la fenêtre (JFrame),
 * la connexion réseau au serveur Python et le rendu graphique de la simulation.
 */
public class ClientInterface extends JFrame {

    // --- Configuration Fixe ---
    private final int CELL_SIZE = 30; // Taille en pixels pour dessiner une unité de grille
    private final float FOV_RADIUS_UNITS = 1.0f; // Rayon de vision utilisé pour dessiner le cône des agents
    private final String SERVER_HOST = "127.0.0.1"; // Adresse IP du serveur Python
    private final int SERVER_PORT = 5001; // Port d'écoute du serveur

    // --- Composants UI de Navigation et Contrôle ---
    private CardLayout cardLayout; // Gère le basculement entre l'écran MENU et l'écran GAME
    private JPanel mainContainer; // Conteneur principal utilisant CardLayout
    private SimulationPanel simulationPanel; // Panneau de dessin de la carte de la ville
    private JTable statsTable; // Tableau des statistiques détaillées par agent
    private DefaultTableModel tableModel; // Modèle de données pour le tableau des agents
    private JSlider speedSlider; // Curseur pour ajuster la vitesse de la simulation

    // --- Composants UI Stats Globales (Mis à jour par les trames STATS) ---
    private JLabel lblMoyEnergie;
    private JLabel lblMoyStress;
    private JLabel lblMoyArgent;
    private JLabel lblNbVivants;
    private JLabel lblNbMorts;
    private JLabel lblNbOccupes;

    // --- Réseau ---
    private PrintWriter out; // Flux d'écriture vers le serveur (pour envoyer les commandes SCENARIO, SPEED, STOP)
    private Socket socket; // Socket de connexion TCP
    private volatile boolean connected = false; // État de la connexion (volatile pour l'accès inter-thread)

    // --- Données Simulation (Thread Safe) ---
    private int gridWidth = 35; // Largeur de la grille (initiale, mise à jour par CONFIG)
    private int gridHeight = 23; // Hauteur de la grille (initiale, mise à jour par CONFIG)

    // Listes thread-safe (CopyOnWriteArrayList) pour stocker l'état actuel de la simulation
    private List<AgentInfo> agents = new CopyOnWriteArrayList<>();
    private List<LieuInfo> lieux = new CopyOnWriteArrayList<>();

    // Record (Java 16+) pour stocker les informations d'un agent reçues par le réseau
    record AgentInfo(String nom, float x, float y, float nrj, float stress, float arg, String etat, float ang) {}
    // Record pour stocker les informations d'un lieu reçues par le réseau
    record LieuInfo(String type, float x, float y, boolean occupe) {}

    // ========================================================================
    // CONSTRUCTEUR ET INITIALISATION DE LA FENÊTRE
    // ========================================================================

    public ClientInterface() {
        setTitle("Super Simulation Multi-Agent | Interface Client");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        // Ajuste la taille de la fenêtre en fonction de la taille de grille initiale
        resizeWindow(gridWidth, gridHeight);
        setLocationRelativeTo(null); // Centre la fenêtre sur l'écran

        // Initialisation du système de navigation (CardLayout)
        cardLayout = new CardLayout();
        mainContainer = new JPanel(cardLayout);

        // Ajout des deux écrans principaux au conteneur
        mainContainer.add(createMenuPanel(), "MENU");
        mainContainer.add(createGameInterface(), "GAME");

        add(mainContainer);
        setVisible(true); // Rend la fenêtre visible (commence sur l'écran MENU)

        // Lance la boucle de gestion du réseau (connexion/écoute) dans un thread séparé
        new Thread(this::networkLoop).start();
    }

    /**
     * Ajuste la taille de la fenêtre et du panneau de simulation en fonction
     * des dimensions de grille reçues par la trame CONFIG.
     */
    private void resizeWindow(int w, int h) {
        int width = (w * CELL_SIZE) + 450; // Grille + Largeur du panneau de stats/contrôles
        int height = (h * CELL_SIZE) + 150; // Grille + Hauteur de la zone de contrôle du bas
        // Assure une taille minimale
        width = Math.max(width, 1050);
        height = Math.max(height, 750);
        setSize(width, height);
        if (simulationPanel != null) {
            // Met à jour la taille du panneau de dessin pour qu'il s'adapte à la grille
            simulationPanel.setPreferredSize(new Dimension(w * CELL_SIZE, h * CELL_SIZE));
        }
        revalidate(); // Force le re-calcul et le re-dessin des composants
    }

    // ========================================================================
    // CONSTRUCTION DE L'INTERFACE (PANNEAUX)
    // ========================================================================

    /**
     * Crée le panneau d'accueil et de sélection de scénario (MENU).
     */
    private JPanel createMenuPanel() {
        JPanel menu = new JPanel(new GridBagLayout());
        menu.setBackground(new Color(30, 30, 35)); // Fond sombre

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(15, 15, 15, 15); // Espacement autour des composants
        gbc.gridx = 0; // Tous les éléments dans la première colonne (empilement vertical)
        gbc.gridy = 0; // Démarre à la première ligne

        // 1. TITRE PRINCIPAL
        JLabel title = new JLabel("SIMULATION VILLE INTELLIGENTE");
        title.setFont(new Font("Segoe UI", Font.BOLD, 32));
        title.setForeground(new Color(255, 105, 180)); // Couleur du titre
        menu.add(title, gbc);

        // 2. SOUS-TITRE (CORRECTION POUR ASSURER LE POSITIONNEMENT EN DESSOUS)
        // L'incrément de gridy assure que le composant est bien placé sur la ligne suivante.
        gbc.gridy++;
        JLabel subTitle = new JLabel("Choisissez un scénario d'émergence :");
        subTitle.setForeground(Color.CYAN);
        subTitle.setFont(new Font("Arial", Font.PLAIN, 18));
        menu.add(subTitle, gbc); // Le sous-titre est ajouté à gridy=1 (sous le titre)

        // 3. BOUTONS DE SCÉNARIO
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

    /**
     * Crée le panneau principal de la simulation (GAME).
     * Il est structuré en BorderLayout: Centre (Carte), Est (Stats), Sud (Contrôles).
     */
    private JPanel createGameInterface() {
        JPanel gamePanel = new JPanel(new BorderLayout());

        // 1. ZONE CENTRALE (Carte de la ville)
        simulationPanel = new SimulationPanel();
        simulationPanel.setBackground(new Color(45, 45, 50));
        JScrollPane scrollSim = new JScrollPane(simulationPanel);
        scrollSim.setBorder(BorderFactory.createEmptyBorder());
        gamePanel.add(scrollSim, BorderLayout.CENTER);

        // 2. ZONE DROITE (Panneau de statistiques et informations)
        JPanel rightPanel = new JPanel();
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.Y_AXIS));
        rightPanel.setPreferredSize(new Dimension(420, 0)); // Largeur fixe
        rightPanel.setBackground(new Color(60, 60, 65));

        // a. Conteneur des Stats Globales (Moyennes + Répartition)
        JPanel statsPanelContainer = new JPanel(new BorderLayout());
        statsPanelContainer.add(createGlobalStatsPanel(), BorderLayout.NORTH); // Moyennes
        statsPanelContainer.add(createRepartitionPanel(), BorderLayout.CENTER); // Répartition Agents
        statsPanelContainer.setMaximumSize(new Dimension(420, 200));
        rightPanel.add(statsPanelContainer);

        // b. Tableau Stats Agents (Détail)
        String[] columnNames = {"Nom", "Énergie", "Stress", "$", "État"};
        tableModel = new DefaultTableModel(columnNames, 0);
        statsTable = new JTable(tableModel);
        JScrollPane tableScroll = new JScrollPane(statsTable);
        // Configuration de l'apparence du tableau
        statsTable.setBackground(new Color(50, 50, 55));
        statsTable.setForeground(Color.WHITE);
        statsTable.setFillsViewportHeight(true);
        statsTable.setGridColor(Color.DARK_GRAY);
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 90)), "Agents Détaillés", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.LIGHT_GRAY));
        tableScroll.setPreferredSize(new Dimension(420, 400));
        rightPanel.add(tableScroll);

        // c. Légende
        JScrollPane legendScroll = createLegendPanelScrollable();
        legendScroll.setPreferredSize(new Dimension(420, 150));
        legendScroll.setMaximumSize(new Dimension(420, 200));
        rightPanel.add(legendScroll);

        gamePanel.add(rightPanel, BorderLayout.EAST);

        // 3. ZONE BAS (Contrôles)
        JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 10));
        controlPanel.setBackground(new Color(50, 20, 100)); // Bande de contrôle violette

        // Bouton de retour/arrêt
        JButton btnBack = new JButton("⬅ Retour Menu / Stop");
        btnBack.setBackground(new Color(200, 100, 100));
        btnBack.addActionListener(e -> retourMenu());

        // Curseur de vitesse
        speedSlider = new JSlider(1, 10, 2);
        speedSlider.setMajorTickSpacing(1);
        speedSlider.setPaintTicks(true);
        speedSlider.setPaintLabels(true);
        speedSlider.setBackground(new Color(50, 20, 100));
        speedSlider.setForeground(Color.LIGHT_GRAY);
        // Envoi de la commande 'SPEED' dès que l'utilisateur relâche le curseur
        speedSlider.addChangeListener(e -> envoyer("SPEED:" + speedSlider.getValue()));

        JLabel lblSpeed = new JLabel("Vitesse Simulation :");
        lblSpeed.setForeground(Color.WHITE);

        controlPanel.add(btnBack);
        controlPanel.add(lblSpeed);
        controlPanel.add(speedSlider);

        gamePanel.add(controlPanel, BorderLayout.SOUTH);

        return gamePanel;
    }

    /**
     * Crée le panneau affichant les moyennes globales (Énergie, Stress, Argent).
     */
    private JPanel createGlobalStatsPanel() {
        JPanel stats = new JPanel(new GridLayout(3, 2, 5, 5));
        stats.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY), "Moyennes (Vivants)", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.CYAN));
        stats.setBackground(new Color(60, 60, 65));

        // Initialisation des labels qui seront mis à jour par le thread réseau
        lblMoyEnergie = createStatLabel("N/A");
        lblMoyStress = createStatLabel("N/A");
        lblMoyArgent = createStatLabel("N/A");

        addStatRow(stats, "Énergie Moy:", lblMoyEnergie);
        addStatRow(stats, "Stress Moy:", lblMoyStress);
        addStatRow(stats, "Argent Moy:", lblMoyArgent);

        return stats;
    }

    /**
     * Crée le panneau affichant la répartition des agents (Vivants, Morts, Occupés).
     */
    private JPanel createRepartitionPanel() {
        JPanel rep = new JPanel(new GridLayout(3, 2, 5, 5));
        rep.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY), "Répartition", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.CYAN));
        rep.setBackground(new Color(60, 60, 65));

        // Initialisation des labels
        lblNbVivants = createStatLabel("N/A");
        lblNbMorts = createStatLabel("N/A");
        lblNbOccupes = createStatLabel("N/A");

        addStatRow(rep, "Agents Vivants:", lblNbVivants);
        addStatRow(rep, "Agents Morts:", lblNbMorts);
        addStatRow(rep, "Agents Occupés:", lblNbOccupes);

        return rep;
    }

    // Fonctions utilitaires pour créer et ajouter des lignes de statistiques
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

    /**
     * Crée le panneau de légende défilant.
     */
    private JScrollPane createLegendPanelScrollable() {
        JPanel legend = new JPanel();
        legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
        legend.setBackground(new Color(60, 60, 65));
        legend.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Color.GRAY), "Légendes", TitledBorder.LEFT, TitledBorder.TOP,
                new Font("Arial", Font.BOLD, 14), Color.WHITE));

        // Légendes des lieux (couleur et icône)
        legend.add(createLegendItem("🏠 Maison", new Color(70, 70, 90)));
        legend.add(createLegendItem("🏢 Travail", new Color(150, 80, 50)));
        legend.add(createLegendItem("🌳 Parc", new Color(50, 120, 70)));
        legend.add(createLegendItem("🍿 Loisir", new Color(130, 50, 130)));
        legend.add(Box.createVerticalStrut(10));

        // Légendes des états des agents
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

    // Fonction utilitaire pour créer une ligne de légende stylisée avec un symbole de couleur
    private JLabel createLegendItem(String text, Color color) {
        String hex = String.format("#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
        // Utilise HTML pour afficher un symbole carré de couleur (■)
        JLabel l = new JLabel("<html><span style='color:"+hex+"; font-size:14px;'>■</span> " + text + "</html>");
        l.setForeground(Color.LIGHT_GRAY);
        l.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        return l;
    }

    // Fonction utilitaire pour créer des boutons stylisés
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
    // LOGIQUE RÉSEAU ET MISE À JOUR DES DONNÉES
    // ========================================================================

    /**
     * Boucle principale de gestion de la connexion réseau. Elle tente de se connecter
     * et, une fois connectée, écoute en continu les trames de données et les commandes.
     */
    private void networkLoop() {
        while (true) {
            try {
                // Tente d'établir ou de rétablir la connexion si nécessaire
                if (socket == null || socket.isClosed()) {
                    try {
                        socket = new Socket(SERVER_HOST, SERVER_PORT);
                        // out : permet d'écrire vers le serveur (auto-flush activé)
                        out = new PrintWriter(socket.getOutputStream(), true);
                        connected = true;
                        System.out.println("Connecté au serveur Python.");
                    } catch (IOException e) {
                        Thread.sleep(1000); // Attente avant de réessayer la connexion
                        continue;
                    }
                }

                // in : buffer pour lire les données entrantes
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String line;

                // Tampons temporaires pour stocker les données de la trame en cours de réception
                List<AgentInfo> bufferAgents = new ArrayList<>();
                List<LieuInfo> bufferLieux = new ArrayList<>();
                String[] tempStats = null; // Stocke la ligne STATS

                // Lecture ligne par ligne de la trame
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("CONFIG")) {
                        // Trame CONFIG (envoyée au démarrage du serveur ou après un changement de scénario)
                        String[] parts = line.split(";");
                        if (parts.length >= 3) {
                            int w = Integer.parseInt(parts[1]);
                            int h = Integer.parseInt(parts[2]);
                            // Mise à jour de l'UI (taille de grille) dans le thread de l'EDT
                            SwingUtilities.invokeLater(() -> {
                                this.gridWidth = w;
                                this.gridHeight = h;
                                resizeWindow(w, h);
                            });
                        }
                    }
                    else if (line.equals("END")) {
                        // Marqueur de FIN DE TRAME : C'est le signal pour mettre à jour l'UI

                        // Copie des tampons pour l'accès inter-thread final (éviter les modifications pendant l'itération)
                        final List<AgentInfo> finalAgents = new ArrayList<>(bufferAgents);
                        final List<LieuInfo> finalLieux = new ArrayList<>(bufferLieux);
                        final String[] finalStats = tempStats;

                        // 1. Mise à jour des listes partagées (thread-safe)
                        this.agents = new CopyOnWriteArrayList<>(finalAgents);
                        this.lieux = new CopyOnWriteArrayList<>(finalLieux);

                        // 2. Mise à jour de l'interface graphique (sur le thread de l'EDT)
                        SwingUtilities.invokeLater(() -> {
                            if (mainContainer.isVisible()) {
                                simulationPanel.repaint(); // Redessin de la carte
                                updateTable(); // Mise à jour du tableau des agents

                                // Mise à jour des labels de statistiques globales
                                if (finalStats != null && finalStats.length >= 7) {
                                    // Index 1: Énergie, 2: Stress, 3: Argent (Moyennes)
                                    lblMoyEnergie.setText(String.format("%.1f", Double.parseDouble(finalStats[1])));
                                    lblMoyStress.setText(String.format("%.1f", Double.parseDouble(finalStats[2])));
                                    lblMoyArgent.setText(String.format("%.1f", Double.parseDouble(finalStats[3])));

                                    // Index 4: Vivants, 5: Morts, 6: Occupés (Répartition)
                                    lblNbVivants.setText(finalStats[4]);
                                    lblNbMorts.setText(finalStats[5]);
                                    lblNbOccupes.setText(finalStats[6]);
                                }
                            }
                        });

                        // 3. Vider les tampons pour recevoir la prochaine trame
                        bufferAgents.clear();
                        bufferLieux.clear();
                        tempStats = null;

                    }
                    else {
                        // Traitement des lignes de données (AGENT, LIEU, STATS)
                        String[] p = line.split(";");
                        String type = p[0];

                        if (type.equals("AGENT")) {
                            // Format attendu: AGENT;Nom;X;Y;Energie;Stress;Argent;Etat;Angle (9 champs)
                            if (p.length == 9) {
                                bufferAgents.add(new AgentInfo(
                                        p[1],
                                        Float.parseFloat(p[2]), Float.parseFloat(p[3]), // X, Y
                                        Float.parseFloat(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6]), // NRJ, Stress, Arg
                                        p[7], Float.parseFloat(p[8]) // État, Angle
                                ));
                            }
                        }
                        else if (type.equals("MAISON") || type.equals("TRAVAIL") || type.equals("PARC") || type.equals("LOISIR")) {
                            // Format attendu: LIEUTYPE;X;Y;OCCUPE (4 champs, OCCUPE est "1" ou "0")
                            if (p.length >= 3) {
                                boolean occupe = p.length > 3 && p[3].equals("1");
                                bufferLieux.add(new LieuInfo(type, Float.parseFloat(p[1]), Float.parseFloat(p[2]), occupe));
                            }
                        }
                        else if (type.equals("STATS")) {
                            // Format: STATS;MoyNrj;MoyStress;MoyArg;NbVivants;NbMorts;NbOccupes
                            tempStats = p; // Stocke les stats pour le traitement en fin de trame
                        }
                    }
                }
            } catch (Exception e) {
                // Gestion de la déconnexion inopinée
                connected = false;
                socket = null;
                System.out.println("Déconnexion serveur... Tentative de reconnexion: " + e.getMessage());
                try {
                    Thread.sleep(2000); // Pause de reconnexion
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Lance un scénario sur le serveur et bascule sur l'interface de jeu.
     */
    private void lancerScenario(int id) {
        // Affiche 'Calc...' en attendant les premières données STATS
        lblMoyEnergie.setText("Calc...");
        lblMoyStress.setText("Calc...");
        lblMoyArgent.setText("Calc...");
        lblNbVivants.setText("...");
        lblNbMorts.setText("...");
        lblNbOccupes.setText("...");

        envoyer("SCENARIO:" + id); // Envoie la commande de changement de scénario

        // Réinitialisation de la vitesse à 2 (par défaut)
        speedSlider.setValue(2);
        envoyer("SPEED:2");

        cardLayout.show(mainContainer, "GAME"); // Bascule vers l'écran de simulation
    }

    /**
     * Envoie la commande d'arrêt au serveur et revient au menu.
     */
    private void retourMenu() {
        envoyer("STOP"); // Envoie la commande d'arrêt de la simulation
        cardLayout.show(mainContainer, "MENU");
    }

    /**
     * Fonction utilitaire pour envoyer un message au serveur (via le PrintWriter out).
     */
    private void envoyer(String msg) {
        if (out != null) out.println(msg);
    }

    /**
     * Met à jour le contenu du tableau détaillé des statistiques des agents.
     */
    private void updateTable() {
        int newSize = agents.size();

        // Optimisation: ajuste le nombre de lignes du modèle si nécessaire
        if (tableModel.getRowCount() != newSize) {
            tableModel.setRowCount(newSize);
        }

        // Remplissage des lignes avec les données de la liste 'agents'
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
    // PANNEAU DE DESSIN (SimulationPanel)
    // ========================================================================
    /**
     * Classe interne gérant le rendu graphique de la carte de la ville.
     */
    class SimulationPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            // Active l'anti-aliasing pour un rendu plus lisse
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 1. Dessine la grille de fond
            for (int x = 0; x < gridWidth; x++) {
                for (int y = 0; y < gridHeight; y++) {
                    g2.setColor(new Color(50, 50, 55)); // Couleur des cases
                    g2.fillRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                    g2.setColor(new Color(40, 40, 45)); // Couleur des lignes de grille
                    g2.drawRect(x * CELL_SIZE, y * CELL_SIZE, CELL_SIZE, CELL_SIZE);
                }
            }

            // Affiche un message d'attente si la connexion n'est pas établie
            if (!connected) {
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Arial", Font.BOLD, 20));
                g2.drawString("En attente du serveur Python...", 50, 50);
                return;
            }

            // 2. DESSIN LIEUX
            for (LieuInfo l : lieux) {
                // Les coordonnées LieuInfo sont des positions de cases (entiers)
                int x = (int) (l.x * CELL_SIZE);
                int y = (int) (l.y * CELL_SIZE);
                if (l.x >= gridWidth || l.y >= gridHeight) continue; // Évite de dessiner hors limites

                Color color = Color.GRAY;
                String emoji = "?";

                // Détermine l'apparence en fonction du type de lieu
                switch (l.type) {
                    case "MAISON" -> { color = new Color(70, 70, 90); emoji = "🏠"; }
                    case "TRAVAIL" -> { color = new Color(150, 80, 50); emoji = "🏢"; }
                    case "PARC" -> { color = new Color(50, 120, 70); emoji = "🌳"; }
                    case "LOISIR" -> { color = new Color(130, 50, 130); emoji = "🍿"; }
                }

                // Dessin du fond du lieu
                g2.setColor(color);
                g2.fillRect(x, y, CELL_SIZE, CELL_SIZE);
                g2.setColor(color.darker());
                g2.drawRect(x, y, CELL_SIZE, CELL_SIZE);

                // Dessin de l'Emoji au centre de la case
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 20));
                g2.drawString(emoji, x + 2, y + 24);

                // Indicateur d'occupation (petit cercle bleu cyan)
                if (l.occupe) {
                    g2.setColor(Color.CYAN);
                    g2.fillOval(x + 22, y + 2, 6, 6);
                }
            }

            // 3. DESSIN AGENTS
            for (AgentInfo a : agents) {
                if (a.x >= gridWidth || a.y >= gridHeight) continue;
                // Coordonnées centrées de l'agent (milieu de la case)
                int cx = (int) (a.x * CELL_SIZE) + CELL_SIZE / 2;
                int cy = (int) (a.y * CELL_SIZE) + CELL_SIZE / 2;

                // Dessin du FOV (Cône de vision)
                int fovRadiusPixels = (int) (FOV_RADIUS_UNITS * CELL_SIZE);
                int fovDiameterPixels = 2 * fovRadiusPixels;

                // Angle de l'agent (en radians), converti pour le système de coordonnées Swing
                double deg = Math.toDegrees(-a.ang);
                double startAngle = deg - 30; // Début de l'arc (-30 degrés par rapport au centre)
                double extentAngle = 60; // Ouverture de l'arc (60 degrés)

                g2.setColor(new Color(255, 255, 255, 40)); // Cône blanc transparent
                g2.fill(new Arc2D.Double(
                        cx - fovRadiusPixels,
                        cy - fovRadiusPixels,
                        fovDiameterPixels,
                        fovDiameterPixels,
                        startAngle,
                        extentAngle,
                        Arc2D.PIE // Dessine un secteur de cercle
                ));

                // DÉTERMINATION COULEUR AGENT
                Color bodyColor;
                if (a.etat.equals("Mort")) {
                    bodyColor = Color.BLACK;
                } else if (a.etat.equals("Occupé")) {
                    bodyColor = Color.GRAY; // Agent sur un lieu, en phase d'activité
                } else {
                    // Rouge si Énergie faible ou Stress élevé (état critique), sinon Vert
                    bodyColor = (a.stress > 70 || a.nrj < 20) ? Color.RED : new Color(50, 200, 50);
                }

                // Dessin du corps (cercle de 12x12 pixels)
                g2.setColor(bodyColor);
                g2.fillOval(cx - 6, cy - 6, 12, 12);

                // Dessin du contour et du nom
                g2.setColor(Color.WHITE);
                g2.drawOval(cx - 6, cy - 6, 12, 12);
                g2.setFont(new Font("Arial", Font.PLAIN, 10));
                g2.drawString(a.nom, cx - 5, cy - 8);

                // Marqueur si l'agent est mort
                if (a.etat.equals("Mort")) {
                    g2.setColor(Color.RED);
                    g2.setFont(new Font("Arial", Font.BOLD, 10));
                    g2.drawString("X", cx - 3, cy + 4);
                }
            }
        }
    }

    // Point d'entrée de l'application
    public static void main(String[] args) {
        // Tente d'utiliser le look and feel du système d'exploitation pour une meilleure intégration visuelle
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        // Lance l'application dans le thread de répartition des événements de Swing (EDT)
        SwingUtilities.invokeLater(() -> new ClientInterface());
    }
}