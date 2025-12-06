
import threading
import time
import random
import json
import os
from deplacerast import generer_lieu
from fonctionsPrincipales import cycle, generer_nom_unique

# Chargement Config
try:
    with open("config_scenarios.json", "r", encoding="utf-8") as f:
        SCENARIOS_CONFIG = json.load(f)
except Exception as e:
    print(f"ERREUR CRITIQUE: Impossible de lire config_scenarios.json : {e}")
    # Fallback minimaliste
    SCENARIOS_CONFIG = {"1": {
        "parametres": {"taille_grille": [30, 20], "nombre_agents": 5, "argent_initial": 50, "energie_initiale": 100,
                       "lieux": {"maisons": 5, "travail": 1, "parcs": 1, "loisirs": 1},
                       "profil_dominant": "Equilibre"}}}


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

        # --- NOUVEAU: Vitesse actuelle pour l'amortissement ---
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


class Ville:
    def __init__(self, simulation, largeur, hauteur, params_lieux):
        self.largeur = largeur
        self.hauteur = hauteur
        self.simulation = simulation
        self.agents = []
        self.maisons = []
        self.tous_lieux = []
        self.coordonnees_occupees = set()
        self.lock = threading.Lock()

        # 1. Maisons
        for _ in range(params_lieux["maisons"]):
            for _ in range(100):
                px, py = random.randint(0, largeur - 1), random.randint(0, hauteur - 1)
                if (px, py) not in self.coordonnees_occupees:
                    self.maisons.append((px, py))
                    self.coordonnees_occupees.add((px, py))
                    break

        # 2. Lieux
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


class Simulation:
    def __init__(self, id_scenario="1"):
        self.running = False
        self.agents = []
        self.vitesse_simulation_factor = 2

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

            # Profil
            prof = conf["profil_dominant"] if random.random() < 0.7 else random.choice(
                ["Bosseur", "Casanier", "Equilibre"])

            a = Agent(nom, maison, trav, parc, lois, conf["energie_initiale"], 10, conf["argent_initial"], self.ville,
                      prof)
            self.agents.append(a)

        self.ville.agents = self.agents

    def set_vitesse(self, v):
        try:
            self.vitesse_simulation_factor = int(v)
        except:
            pass

    def demarrer(self):
        self.running = True
        for a in self.agents: a.start()

    def arreter(self):
        self.running = False
        for a in self.agents: a.stop_agent()