<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="UTF-8">
    <title>Rapport de Projet - Simulation Multi-Agents Urbaine</title>
    <style>
        body {
            font-family: Arial, sans-serif;
            line-height: 1.6;
            max-width: 800px;
            margin: 0 auto;
            padding: 20px;
            color: #333;
        }
        h1 {
            color: #2c3e50;
            border-bottom: 3px solid #3498db;
            padding-bottom: 10px;
        }
        h2 {
            color: #34495e;
            margin-top: 30px;
        }
        ul {
            list-style: none;
            padding-left: 0;
        }
        ul li {
            margin-bottom: 15px;
            padding: 10px;
            border-left: 4px solid #f39c12;
            background-color: #ecf0f1;
        }
        .section-header {
            color: #2980b9;
            font-weight: bold;
            font-size: 1.1em;
            margin-top: 20px;
        }
    </style>
</head>
<body>

    <h1>Simulation Multi-Agents Urbaine (SMA) </h1>
    <p>Ce projet implémente une <strong>Simulation Multi-Agents (SMA)</strong> pour modéliser les dynamiques sociales (stress, énergie, argent) au sein d'un environnement urbain contraint.</p>

    <hr>

    <h2> Contributeurs et Rôles Spécifiques</h2>
    <p>Ce projet est le fruit d'une collaboration avec une répartition claire des responsabilités :</p>

    <ul>
        <li>
            <strong style="color:#2c3e50;">[Nom/Pseudo du Contributeur 1]</strong>
            <ul>
                <li><strong>Architecture Principale :</strong> Création et gestion du fichier ClassPrincipale.py (classes Simulation, Agent, Ville).</li>
                <li><strong>Interface Client :</strong> Développement de l'interface graphique ClientInterface.java.</li>
            </ul>
        </li>
        <li>
            <strong style="color:#2c3e50;">[Nom/Pseudo du Contributeur 2]</strong>
            <ul>
                <li><strong>Moteur de Déplacement :</strong> Implémentation complète du pathfinding et du mouvement dans deplacerast.py.</li>
            </ul>
        </li>
        <li>
            <strong style="color:#2c3e50;">[Nom/Pseudo du Contributeur 3]</strong>
            <ul>
                <li><strong>Logique Agent & Stats :</strong> Création des fonctions de décision et de mise à jour des stats dans fonctionsPrincipales.py.</li>
                <li><strong>Réseau & Configuration :</strong> Développement du serveur_socket.py et gestion du fichier config_scenarios.json.</li>
            </ul>
        </li>
    </ul>

    <hr>

    <h2> Points Clés Techniques</h2>

    <div class="section-header">1. Modèle de Concurrence</div>
    <ul>
        <li><strong>Threads :</strong> Chaque agent s'exécute dans son propre thread Python (<code>threading.Thread</code>).</li>
        <li><strong>Synchronisation :</strong> Utilisation d'un <code>Lock</code> (<code>threading.Lock</code>) pour sécuriser l'accès aux ressources partagées (e.g., occupation des lieux).</li>
    </ul>

    <div class="section-header">2. Mouvement Hybride (A* + Boids)</div>
    <p>Le déplacement combine deux techniques essentielles pour assurer une navigation à la fois intelligente et fluide :</p>
    <ul>
        <li><strong>A* (Planification Globale) :</strong> Calcule le chemin le plus court pour les agents, en respectant les obstacles statiques (murs, propriété privée).</li>
        <li><strong>Boids (Comportement Local) :</strong> Utilise des forces de <strong>répulsion/attraction</strong> pour gérer le mouvement fluide et éviter les collisions dynamiques entre agents (le <strong>Field of View - FOV</strong> est un paramètre critique pour la détection des voisins).</li>
    </ul>

</body>
</html>
