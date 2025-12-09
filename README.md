# Simulation Multi-Agents Urbaine (SMA)

Ce projet implémente une **Simulation Multi-Agents (SMA)** pour modéliser les dynamiques sociales (stress, énergie, argent) au sein d'un environnement urbain contraint.

---

## 1. Contributeurs et Rôles Spécifiques

Ce projet est le fruit d'une collaboration avec une répartition claire des responsabilités :

### [Sara/SaraFenina]

* **Architecture Principale :** Création et gestion du fichier `ClassPrincipale.py` (classes `Simulation`, `Agent`, `Ville`).
* **Interface Client :** Développement de l'interface graphique `ClientInterface.java`.

### [Prisca/ambinintsoaprisca]

* **Moteur de Déplacement :** Implémentation complète du pathfinding et du mouvement dans `deplacerast.py`.

### [Sarah/Sarhaivie]

* **Logique Agent & Stats :** Création des fonctions de décision et de mise à jour des stats dans `fonctionsPrincipales.py`.
* **Réseau & Configuration :** Développement du `serveur_socket.py` et gestion du fichier `config_scenarios.json`.

---

## 2. Points Clés Techniques

### A. Modèle de Concurrence

* **Threads :** Chaque agent s'exécute dans son propre thread Python (`threading.Thread`).
* **Synchronisation :** Utilisation d'un `Lock` (`threading.Lock`) pour sécuriser l'accès aux ressources partagées (e.g., occupation des lieux).

### B. Mouvement Hybride (A* + Steering Behaviors/Boids)

Le déplacement combine deux techniques essentielles pour assurer une navigation à la fois intelligente et fluide :

* **A* (Planification Globale) :** Calcule le chemin le plus court pour les agents, en respectant les obstacles statiques (murs, propriété privée).
* **Steering Behaviors (Comportement Local) :** Utilise des forces de **répulsion/attraction** pour gérer le mouvement fluide et éviter les collisions dynamiques entre agents. Le **Field of View (FOV)** est un paramètre critique pour la détection des voisins et l'application des forces.
