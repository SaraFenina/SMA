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
    SCENARIOS_CONFIG = {"1": {
        "parametres": {"taille_grille": [30, 20], "nombre_agents": 5, "argent_initial": 50, "energie_initiale": 100,
                       "lieux": {"maisons": 5, "travail": 1, "parcs": 1, "loisirs": 1},
                       "profil_dominant": "Equilibre"}}}


# ===========================================================
# CLASSE POUR L'AGRÉGATION DE STATS
# ===========================================================
# ===========================================================
# CLASSE POUR L'AGRÉGATION DE STATS
# ===========================================================
class StatsAggregator(threading.Thread):
    def __init__(self, simulation, intervalle=10.0):
        super().__init__()
        self.simulation = simulation
        self.intervalle = intervalle
        self.running = True
        self.stats_log = []

    def run(self):
        while self.running:
            for _ in range(int(self.intervalle * 10)):
                if not self.running: return
                time.sleep(0.1)

            if not self.running: break

            with self.simulation.ville.lock:
                if self.simulation.running and self.simulation.agents:
                    # 'moyennes' est obtenu depuis calculer_moyennes qui utilise 'NbMorts', 'NbVivants', 'NbOccupes'
                    moyennes = calculer_moyennes(self.simulation.agents)
                    timestamp = time.strftime("%H:%M:%S", time.localtime())

                    log_entry = {
                        "temps": timestamp,
                        "scenario": self.simulation.id_scenario_actuel,
                        "moyenne_energie": f"{moyennes['Energie']:.2f}",
                        "moyenne_stress": f"{moyennes['Stress']:.2f}",
                        "moyenne_argent": f"{moyennes['Argent']:.2f}",
                        # Les clés du dictionnaire log_entry sont définies ici:
                        "agents_vivants": moyennes['NbVivants'],
                        "agents_morts": moyennes['NbMorts'],  # <-- CLEF CORRECTE
                        "agents_occupes": moyennes['NbOccupes']  # <-- CLEF CORRECTE
                    }
                    self.stats_log.append(log_entry)

                    # CORRECTION DE LA LIGNE D'AFFICHAGE (Utilisation des clés 'agents_morts' et 'agents_occupes')
                    print(
                        f"[STATS 10s] {timestamp} - NRJ:{log_entry['moyenne_energie']} STR:{log_entry['moyenne_stress']} ARG:{log_entry['moyenne_argent']} | V:{log_entry['agents_vivants']} M:{log_entry['agents_morts']} O:{log_entry['agents_occupes']}"
                    )

    def stop(self):
        self.running = False

    def reset_stats(self):
        self.stats_log = []


# ===========================================================
# CLASSE AGENT
# ===========================================================
class Agent(threading.Thread):
    def __init__(self, nom, maison, travail, parc, loisir, energie, stress, argent, ville, profil_type="Equilibre"):
        super().__init__()
        self.nom = nom
        self.x, self.y = float(maison[0]), float(maison[1])
        self.maison = maison
        self.travail = travail
        self.parc = parc
        self.loisir = loisir
        self.energie = float(energie)
        self.stress = float(stress)
        self.argent = float(argent)
        self.ville = ville
        self.vivant = True
        self.running = True
        self.etat = "Repos"
        self.destination = None
        self.temps_activite = 0
        self.angle_vue = 0.0
        self.chemin = []
        self.chemin_goal = None
        self.vx = 0.0
        self.vy = 0.0

        self.preferences = {"Maison": 0.2, "Travail": 0.3, "Loisir": 0.3, "Parc": 0.2}
        if profil_type == "Bosseur":
            self.preferences = {"Maison": 0.1, "Travail": 0.8, "Loisir": 0.05, "Parc": 0.05}
        elif profil_type == "Casanier":
            self.preferences = {"Maison": 0.8, "Travail": 0.1, "Loisir": 0.05, "Parc": 0.05}

    def run(self):
        cycle(self)

    def stop_agent(self):
        self.running = False


# ===========================================================
# CLASSE VILLE
# ===========================================================
class Ville:
    def __init__(self, simulation, largeur, hauteur, params_lieux):
        self.largeur = largeur
        self.hauteur = hauteur
        self.simulation = simulation
        self.agents = []
        self.maisons = []
        self.tous_lieux = []
        self.coordonnees_occupees = set()
        self.lock = threading.Lock()  # Le lock est maintenu pour la sécurité des ressources partagées

        # 1. Maisons
        for _ in range(params_lieux["maisons"]):
            for _ in range(100):
                px, py = random.randint(0, largeur - 1), random.randint(0, hauteur - 1)
                if (px, py) not in self.coordonnees_occupees:
                    self.maisons.append((px, py))
                    self.coordonnees_occupees.add((px, py))
                    break

        # 2. Lieux (Travaux, Parcs, Loisirs)
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
# CLASSE SIMULATION (Gestion simple des threads)
# ===========================================================
class Simulation:
    def __init__(self, id_scenario="1"):
        self.running = False
        self.agents = []
        self.vitesse_simulation_factor = 2
        self.id_scenario_actuel = id_scenario

        conf = SCENARIOS_CONFIG.get(str(id_scenario), SCENARIOS_CONFIG["1"])["parametres"]
        w, h = conf["taille_grille"]

        self.ville = Ville(self, w, h, conf["lieux"])

        # Agents
        noms = set()
        nb = min(conf["nombre_agents"], len(self.ville.maisons))

        for i in range(nb):
            maison = self.ville.maisons[i]
            trav = random.choice(self.ville.travails + [None])
            lois = random.choice(self.ville.loisirs)
            parc = random.choice(self.ville.parcs)
            nom = generer_nom_unique(noms)

            prof = conf["profil_dominant"] if random.random() < 0.7 else random.choice(
                ["Bosseur", "Casanier", "Equilibre"])

            a = Agent(nom, maison, trav, parc, lois, conf["energie_initiale"], 10, conf["argent_initial"], self.ville,
                      prof)
            self.agents.append(a)

        self.ville.agents = self.agents

        self.stats_aggregator = StatsAggregator(self)

    def set_vitesse(self, v):
        try:
            self.vitesse_simulation_factor = int(v)
        except:
            pass

    def demarrer(self):
        self.running = True

        # Démarrage des threads agents
        for a in self.agents: a.start()

        # Démarrage Stats
        self.stats_aggregator.reset_stats()
        if not self.stats_aggregator.is_alive():
            self.stats_aggregator.start()

    def arreter(self):
        print("Arrêt de la simulation demandé.")
        self.running = False

        # Demander l'arrêt à tous les agents
        for a in self.agents: a.stop_agent()

        # Arrêt du thread de stats
        if self.stats_aggregator and self.stats_aggregator.is_alive():
            self.stats_aggregator.stop()
            self.stats_aggregator.join()

        # Attendre la fin de tous les threads agents restants
        for a in self.agents:
            if a.is_alive():
                a.join()
        print("Tous les threads sont terminés.")