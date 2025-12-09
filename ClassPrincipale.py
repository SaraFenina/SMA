import threading
import time
import random
import json
import os
import math
from deplacerast import generer_lieu
from fonctionsPrincipales import cycle, generer_nom_unique, calculer_moyennes

# Chargement Config
try:
    with open("config_scenarios.json", "r", encoding="utf-8") as f:
        SCENARIOS_CONFIG = json.load(f)
except Exception as e:
    print(f"ERREUR CRITIQUE: Impossible de lire config_scenarios.json : {e}")
    # Configuration par défaut si le fichier est manquant ou invalide
    SCENARIOS_CONFIG = {"1": {
        "parametres": {"taille_grille": [30, 20], "nombre_agents": 5, "argent_initial": 50, "energie_initiale": 100,
                       "lieux": {"maisons": 5, "travail": 1, "parcs": 1, "loisirs": 1},
                       "profil_dominant": "Equilibre"}}}


# ===========================================================
# CLASSE POUR L'AGRÉGATION DE STATS
# ===========================================================

# Thread d'arrière-plan pour collecter et afficher les statistiques globales de la simulation à intervalles réguliers.
class StatsAggregator(threading.Thread):
    def __init__(self, simulation, intervalle=10.0):
        super().__init__()
        self.simulation = simulation
        self.intervalle = intervalle
        self.running = True
        self.stats_log = []

    # Méthode exécutée lorsque le thread démarre.
    def run(self):
        while self.running:
            # Attend l'intervalle spécifié
            for _ in range(int(self.intervalle * 10)):
                if not self.running: return
                time.sleep(0.1)

            if not self.running: break

            # Accès sécurisé aux données de la ville (liste d'agents)
            with self.simulation.ville.lock:
                if self.simulation.running and self.simulation.agents:
                    # Calcule les moyennes et la répartition
                    moyennes = calculer_moyennes(self.simulation.agents)
                    timestamp = time.strftime("%H:%M:%S", time.localtime())

                    log_entry = {
                        "temps": timestamp,
                        "scenario": self.simulation.id_scenario_actuel,
                        "moyenne_energie": f"{moyennes['Energie']:.2f}",
                        "moyenne_stress": f"{moyennes['Stress']:.2f}",
                        "moyenne_argent": f"{moyennes['Argent']:.2f}",
                        "agents_vivants": moyennes['NbVivants'],
                        "agents_morts": moyennes['NbMorts'],
                        "agents_occupes": moyennes['NbOccupes']
                    }
                    self.stats_log.append(log_entry)

                    # Affichage console des stats agrégées
                    print(
                        f"[STATS 10s] {timestamp} - NRJ:{log_entry['moyenne_energie']} STR:{log_entry['moyenne_stress']} ARG:{log_entry['moyenne_argent']} | V:{log_entry['agents_vivants']} M:{log_entry['agents_morts']} O:{log_entry['agents_occupes']}"
                    )

    # Arrête la boucle du thread.
    def stop(self):
        self.running = False

    # Réinitialise l'historique des statistiques.
    def reset_stats(self):
        self.stats_log = []


# ===========================================================
# CLASSE AGENT
# ===========================================================

# Représente une entité autonome dans la simulation, héritant de threading.Thread pour son exécution indépendante.
class Agent(threading.Thread):
    def __init__(self, nom, maison, travail, parc, loisir, energie, stress, argent, ville, profil_type="Equilibre"):
        super().__init__()
        self.nom = nom
        # Position initiale à la maison
        self.x, self.y = float(maison[0]), float(maison[1])
        self.maison = maison
        # Lieux attribués
        self.travail = travail
        self.parc = parc
        self.loisir = loisir
        # Stats vitales
        self.energie = float(energie)
        self.stress = float(stress)
        self.argent = float(argent)
        self.ville = ville
        self.vivant = True
        self.running = True
        self.etat = "Repos" # Repos, Attente, Vers [Lieu], Occupé, Mort
        self.destination = None
        self.temps_activite = 0 # Compteur d'activité lorsqu'occupé
        # Mouvement/Physique
        self.angle_vue = 0.0
        self.chemin = [] # Chemin A* actuel
        self.chemin_goal = None
        self.vx = 0.0 # Vitesse en x
        self.vy = 0.0 # Vitesse en y

        # Définition des préférences de destination selon le profil psychologique
        self.preferences = {"Maison": 0.2, "Travail": 0.3, "Loisir": 0.3, "Parc": 0.2}
        if profil_type == "Bosseur":
            self.preferences = {"Maison": 0.1, "Travail": 0.8, "Loisir": 0.05, "Parc": 0.05}
        elif profil_type == "Casanier":
            self.preferences = {"Maison": 0.8, "Travail": 0.1, "Loisir": 0.05, "Parc": 0.05}

    # Lance le cycle de vie de l'agent (appel à fonctionsPrincipales.cycle)
    def run(self):
        cycle(self)

    # Demande l'arrêt de la boucle de l'agent.
    def stop_agent(self):
        self.running = False


# ===========================================================
# CLASSE VILLE
# ===========================================================

# Représente l'environnement de la simulation et gère la création/positionnement des lieux.
class Ville:
    def __init__(self, simulation, largeur, hauteur, params_lieux):
        self.largeur = largeur
        self.hauteur = hauteur
        self.simulation = simulation
        self.agents = [] # Référence aux agents
        self.maisons = [] # Liste des coordonnées des maisons (tuples)
        self.tous_lieux = [] # Liste des lieux complexes (dicts : Travail, Parc, Loisir)
        self.coordonnees_occupees = set() # Ensemble des coordonnées de grille occupées par des bâtiments
        self.lock = threading.Lock()  # Lock pour sécuriser l'accès concurrentiel aux ressources partagées (e.g., places)

        # 1. Génération des Maisons (points uniques)
        for _ in range(params_lieux["maisons"]):
            for _ in range(100):
                px, py = random.randint(0, largeur - 1), random.randint(0, hauteur - 1)
                if (px, py) not in self.coordonnees_occupees:
                    self.maisons.append((px, py))
                    self.coordonnees_occupees.add((px, py))
                    break

        # 2. Génération des Lieux (blocs rectangulaires de places)
        self.parcs, self.travails, self.loisirs = [], [], []

        for _ in range(params_lieux["parcs"]):
            l = generer_lieu(largeur, hauteur, "P", 20, self.coordonnees_occupees)
            if l: self.parcs.append(l)

        for _ in range(params_lieux["travail"]):
            l = generer_lieu(largeur, hauteur, "T", 10, self.coordonnees_occupees)
            if l: self.travails.append(l)

        for _ in range(params_lieux["loisirs"]):
            l = generer_lieu(largeur, hauteur, "L", 5, self.coordonnees_occupees)
            if l: self.loisirs.append(l)

        self.tous_lieux = self.parcs + self.travails + self.loisirs


# ===========================================================
# CLASSE SIMULATION
# ===========================================================

# Classe principale gérant le cycle de vie de la simulation (démarrage/arrêt des threads, configuration du scénario).
class Simulation:
    def __init__(self, id_scenario="1"):
        self.running = False
        self.agents = []
        self.vitesse_simulation_factor = 2 # Multiplicateur de la vitesse du pas de temps de l'agent (réglable par client)
        self.id_scenario_actuel = id_scenario

        # Chargement des paramètres spécifiques au scénario
        conf = SCENARIOS_CONFIG.get(str(id_scenario), SCENARIOS_CONFIG["1"])["parametres"]
        w, h = conf["taille_grille"]

        self.ville = Ville(self, w, h, conf["lieux"])

        # Création des agents
        noms = set()
        nb = min(conf["nombre_agents"], len(self.ville.maisons))

        for i in range(nb):
            maison = self.ville.maisons[i]
            # Attribution aléatoire d'un lieu de travail/loisir/parc
            trav = random.choice(self.ville.travails + [None])
            lois = random.choice(self.ville.loisirs)
            parc = random.choice(self.ville.parcs)
            nom = generer_nom_unique(noms)

            # Attribution du profil dominant ou aléatoire
            prof = conf["profil_dominant"] if random.random() < 0.7 else random.choice(
                ["Bosseur", "Casanier", "Equilibre"])

            a = Agent(nom, maison, trav, parc, lois, conf["energie_initiale"], conf["stress_initial"], conf["argent_initial"], self.ville,
                      prof)
            self.agents.append(a)

        self.ville.agents = self.agents

        self.stats_aggregator = StatsAggregator(self)

    # Définit le facteur de vitesse pour le déplacement des agents.
    def set_vitesse(self, v):
        try:
            self.vitesse_simulation_factor = int(v)
        except:
            pass

    # Démarre tous les threads (Agents et Aggregator).
    def demarrer(self):
        self.running = True

        # Démarrage des threads agents
        for a in self.agents: a.start()

        # Démarrage Stats
        self.stats_aggregator.reset_stats()
        if not self.stats_aggregator.is_alive():
            self.stats_aggregator.start()

    # Arrête la simulation en demandant l'arrêt à tous les threads et en attendant leur terminaison.
    def arreter(self):
        print("Arrêt de la simulation demandé.")
        self.running = False

        # Demander l'arrêt à tous les agents
        for a in self.agents: a.stop_agent()

        # Arrêt du thread de stats
        if self.stats_aggregator and self.stats_aggregator.is_alive():
            self.stats_aggregator.stop()
            self.stats_aggregator.join() # Attend que le thread se termine

        # Attendre la fin de tous les threads agents restants
        for a in self.agents:
            if a.is_alive():
                a.join() # Attend que le thread se termine
        print("Tous les threads sont terminés.")