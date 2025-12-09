"""
    Ecrit par Guilleminot, Sarah

"""

import random
import time
from deplacerast import deplacer


# ===========================================================
# FONCTIONS UTILITAIRES DE BASE
# ===========================================================

# Génère un nom d'agent unique (une lettre majuscule suivie d'un chiffre)
# Paramètres :
#   - noms (set) : L'ensemble des noms déjà utilisés pour garantir l'unicité.
# Retourne :
#   - str : Un nouveau nom unique.
def generer_nom_unique(noms):
    while True:
        s = chr(random.randint(65, 90)) + str(random.randint(0, 9))
        if s not in noms:
            noms.add(s)
            return s


# Calcule la moyenne des statistiques vitales des agents vivants (Énergie, Stress, Argent)
# et agrège le compte des agents par état (Vivants, Morts, Occupés).
# Paramètres :
#   - agents (list) : Liste de tous les objets Agent de la simulation.
# Retourne :
#   - dict : Les moyennes et les comptes agrégés.
def calculer_moyennes(agents):
    """Calcule la moyenne des stats et la répartition des agents (vivants/morts/occupés)."""

    nb_vivants_bool = sum(1 for a in agents if a.vivant)

    # Répartitions basées sur l'état
    nb_morts_etat = sum(1 for a in agents if a.etat == "Mort")
    nb_occupes_etat = sum(1 for a in agents if a.etat == "Occupé")

    if nb_vivants_bool == 0:
        return {
            "Energie": 0.0, "Stress": 0.0, "Argent": 0.0,
            "NbVivants": 0, "NbMorts": nb_morts_etat, "NbOccupes": nb_occupes_etat
        }

    somme_energie = sum(a.energie for a in agents if a.vivant)
    somme_stress = sum(a.stress for a in agents if a.vivant)
    somme_argent = sum(a.argent for a in agents if a.vivant)

    return {
        "Energie": somme_energie / nb_vivants_bool,
        "Stress": somme_stress / nb_vivants_bool,
        "Argent": somme_argent / nb_vivants_bool,
        "NbVivants": nb_vivants_bool,
        "NbMorts": nb_morts_etat,
        "NbOccupes": nb_occupes_etat
    }


# ===========================================================
# LOGIQUE DE DÉCISION DES AGENTS
# ===========================================================

# Détermine l'ensemble des coordonnées de la grille que l'agent doit éviter lors du pathfinding.
# Ces coordonnées représentent la 'propriété privée' (les lieux autres que la destination de l'agent).
# Paramètres :
#   - agent (Agent) : L'objet Agent dont on calcule les obstacles.
# Retourne :
#   - set : Un ensemble de tuples (x, y) à éviter.
def get_obstacles_stricts(agent):
    """Détermine les coordonnées de la grille que l'agent ne peut pas traverser (propriété privée)."""
    obstacles = set()

    # Détermine les places spécifiques visées par l'agent (pour ne pas les considérer comme obstacles)
    dest_places = []
    if isinstance(agent.destination, dict):
        dest_places = [(p["x"], p["y"]) for p in agent.destination["places"]]
    elif isinstance(agent.destination, tuple):  # Maison
        dest_places = [agent.destination]

    # Ajoute tous les lieux (sauf les parcs "P" qui sont publics)
    for lieu in agent.ville.tous_lieux:
        if lieu.get("symbole") == "P": continue
        for p in lieu["places"]:
            c = (p["x"], p["y"])
            if c not in dest_places: obstacles.add(c)

    # Ajoute toutes les maisons autres que celle de l'agent
    for m in agent.ville.maisons:
        if m != agent.maison and m not in dest_places: obstacles.add(m)

    return obstacles


# Gère la logique de prise de décision pour l'agent (où aller ensuite).
# Gère la libération de l'ancienne place (si l'agent était occupé) et le choix de la nouvelle destination.
# Paramètres :
#   - agent (Agent) : L'objet Agent dont la décision doit être mise à jour.
def choisir_destination(agent):
    """Logique de décision pour choisir la prochaine destination de l'agent."""
    # 1. Libération de la place si l'activité est terminée
    if agent.etat == "Occupé":
        if agent.temps_activite > 0: return  # L'activité n'est pas terminée

        # Libération de la place (Accès concurrentiel, nécessite un lock)
        # On itère sur tous les lieux pour trouver la place que l'agent occupait
        for l in agent.ville.tous_lieux:
            for p in l["places"]:
                with agent.ville.lock:  # Sécurité des threads pour les ressources partagées
                    if agent in p["occupant"]: p["occupant"].remove(agent)

        agent.etat = "Attente"
        agent.destination = None
        agent.chemin = []

    # Si une destination est déjà définie (et qu'on n'est pas en état "Occupé"), on ne fait rien
    if agent.destination: return

    # 2. Condition de priorité : Rentrée à la maison si l'énergie est critique
    if agent.energie < 20:
        agent.destination = agent.maison
        agent.etat = "Rentre"
        return

    # 3. Choix de destination pondéré
    opts = ["Maison"]
    # Poids par défaut de la maison
    w = [agent.preferences.get("Maison", 0.2)]

    # Ajoute les options Travail, Loisir, Parc en fonction de leur disponibilité/condition
    if agent.travail:
        opts.append("Travail");
        w.append(agent.preferences.get("Travail", 0.4))
    if agent.loisir and agent.argent > 5:  # Condition : doit avoir assez d'argent pour les loisirs
        opts.append("Loisir");
        w.append(agent.preferences.get("Loisir", 0.3))
    if agent.parc:
        opts.append("Parc");
        w.append(agent.preferences.get("Parc", 0.1))

    # Sélection basée sur les poids/préférences
    c = random.choices(opts, weights=w, k=1)[0]

    # 4. Affectation de la destination et mise à jour de l'état
    if c == "Maison":
        agent.destination = agent.maison
    elif c == "Travail":
        agent.destination = agent.travail
    elif c == "Loisir":
        agent.destination = agent.loisir
    elif c == "Parc":
        agent.destination = agent.parc

    agent.etat = f"Vers {c}"


# Met à jour les statistiques vitales de l'agent (Énergie, Stress, Argent) et vérifie la condition de mort.
# L'impact des activités dépend de l'état de l'agent (en mouvement ou occupé).
# Paramètres :
#   - agent (Agent) : L'objet Agent dont les stats doivent être mises à jour.
def mise_a_jour(agent):
    """Mise à jour des stats vitales et vérification de la mort."""
    # 1. Agent en mouvement/attente
    if agent.etat != "Occupé":
        agent.energie -= 0.1
        agent.stress += 0.05
    # 2. Agent occupé (Activité)
    else:
        agent.temps_activite -= 1
        sym = "M"  # Symbole par défaut: Maison
        if isinstance(agent.destination, dict): sym = agent.destination.get("symbole", "M")

        # Mise à jour des stats selon le type de lieu
        if sym == "T":  # Travail
            agent.argent += 1;
            agent.stress += 0.1  # Le travail est stressant
        elif sym == "L":  # Loisir
            agent.argent -= 0.5;
            agent.stress -= 0.5  # Le loisir est payant mais réduit le stress
        elif sym == "P":  # Parc
            agent.stress -= 0.2  # Les parcs réduisent le stress
        else:  # Maison (Repos)
            agent.energie += 0.5;
            agent.stress -= 0.1  # La maison recharge l'énergie et réduit le stress

    # 3. Clamping des valeurs (0 à 100)
    agent.energie = max(0, min(100, agent.energie))
    agent.stress = max(0, min(100, agent.stress))

    # 4. Vérification de la mort
    if agent.energie <= 0 or agent.stress >= 100:
        agent.vivant = False
        agent.etat = "Mort"


# ===========================================================
# CYCLE PRINCIPAL (THREAD AGENT)
# ===========================================================

# Boucle principale d'exécution pour chaque agent. Elle est exécutée par le thread de l'agent.
# Gère la décision, le mouvement (via deplacerast.py) et la mise à jour des stats.
# Paramètres :
#   - agent (Agent) : L'objet Agent qui exécute ce cycle.
def cycle(agent):
    """Boucle principale exécutée par chaque thread Agent (régulation par pause simple)."""
    while agent.vivant and agent.running:

        # Étape 1: Décision de destination
        choisir_destination(agent)

        # Étape 2: Mouvement si une destination est définie et que l'agent n'est pas occupé
        if agent.destination and agent.etat != "Occupé":
            obs = get_obstacles_stricts(agent)
            deplacer(agent, obs, agent.ville.simulation.vitesse_simulation_factor)

            # Étape 3: Vérification de l'arrivée (le chemin est vide)
            if hasattr(agent, "chemin") and len(agent.chemin) == 0:
                agent.etat = "Occupé"

                # Détermination du temps d'activité
                sym = "M"
                if isinstance(agent.destination, dict): sym = agent.destination.get("symbole", "M")

                if sym == "T":  # Travail
                    agent.temps_activite = random.randint(50, 100)
                elif sym == "L":  # Loisir
                    agent.temps_activite = random.randint(30, 60)
                else:  # Maison ou Parc
                    agent.temps_activite = random.randint(20, 40)

                # Prise de place (Accès concurrentiel, nécessite un lock)
                # On marque l'agent comme occupant la place la plus proche
                if isinstance(agent.destination, dict):
                    for p in agent.destination["places"]:
                        if abs(agent.x - p["x"]) < 0.6 and abs(agent.y - p["y"]) < 0.6:
                            with agent.ville.lock:
                                if agent not in p["occupant"]: p["occupant"].append(agent)
                            break

        # Étape 4: Mise à jour des stats vitales
        mise_a_jour(agent)

        # Régulation par pause simple (~20 FPS par agent)
        time.sleep(0.05)