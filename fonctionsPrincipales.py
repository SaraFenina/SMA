import random
import time
# NOTE: Le module 'threading' est conservé mais n'est pas utilisé pour la synchronisation
# import threading
from deplacerast import deplacer


# ===========================================================
# FONCTIONS UTILITAIRES DE BASE
# ===========================================================

def generer_nom_unique(noms):
    while True:
        s = chr(random.randint(65, 90)) + str(random.randint(0, 9))
        if s not in noms:
            noms.add(s)
            return s


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

def get_obstacles_stricts(agent):
    """Détermine les coordonnées de la grille que l'agent ne peut pas traverser (propriété privée)."""
    obstacles = set()

    dest_places = []
    if isinstance(agent.destination, dict):
        dest_places = [(p["x"], p["y"]) for p in agent.destination["places"]]
    elif isinstance(agent.destination, tuple):
        dest_places = [agent.destination]

    for lieu in agent.ville.tous_lieux:
        if lieu.get("symbole") == "P": continue
        for p in lieu["places"]:
            c = (p["x"], p["y"])
            if c not in dest_places: obstacles.add(c)

    for m in agent.ville.maisons:
        if m != agent.maison and m not in dest_places: obstacles.add(m)

    return obstacles


def choisir_destination(agent):
    """Logique de décision pour choisir la prochaine destination de l'agent."""
    if agent.etat == "Occupé":
        if agent.temps_activite > 0: return

        # Libération de la place (Accès concurrentiel, nécessite un lock)
        for l in agent.ville.tous_lieux:
            for p in l["places"]:
                with agent.ville.lock:
                    if agent in p["occupant"]: p["occupant"].remove(agent)

        agent.etat = "Attente"
        agent.destination = None
        agent.chemin = []

    if agent.destination: return

    if agent.energie < 20:
        agent.destination = agent.maison
        agent.etat = "Rentre"
        return

    opts = ["Maison"]
    w = [agent.preferences.get("Maison", 0.2)]

    if agent.travail:
        opts.append("Travail");
        w.append(agent.preferences.get("Travail", 0.4))
    if agent.loisir and agent.argent > 5:
        opts.append("Loisir");
        w.append(agent.preferences.get("Loisir", 0.3))
    if agent.parc:
        opts.append("Parc");
        w.append(agent.preferences.get("Parc", 0.1))

    c = random.choices(opts, weights=w, k=1)[0]

    if c == "Maison":
        agent.destination = agent.maison
    elif c == "Travail":
        agent.destination = agent.travail
    elif c == "Loisir":
        agent.destination = agent.loisir
    elif c == "Parc":
        agent.destination = agent.parc

    agent.etat = f"Vers {c}"


def mise_a_jour(agent):
    """Mise à jour des stats vitales et vérification de la mort."""
    if agent.etat != "Occupé":
        agent.energie -= 0.1
        agent.stress += 0.05
    else:
        agent.temps_activite -= 1
        sym = "M"
        if isinstance(agent.destination, dict): sym = agent.destination.get("symbole", "M")

        if sym == "T":
            agent.argent += 1;
            agent.stress += 0.1
        elif sym == "L":
            agent.argent -= 0.5;
            agent.stress -= 0.5
        elif sym == "P":
            agent.stress -= 0.2
        else:
            agent.energie += 0.5;
            agent.stress -= 0.1

    agent.energie = max(0, min(100, agent.energie))
    agent.stress = max(0, min(100, agent.stress))

    if agent.energie <= 0 or agent.stress >= 100:
        agent.vivant = False
        agent.etat = "Mort"


# ===========================================================
# CYCLE PRINCIPAL (THREAD AGENT)
# ===========================================================

def cycle(agent):
    """Boucle principale exécutée par chaque thread Agent (régulation par pause simple)."""
    while agent.vivant and agent.running:

        choisir_destination(agent)

        if agent.destination and agent.etat != "Occupé":
            obs = get_obstacles_stricts(agent)
            deplacer(agent, obs, agent.ville.simulation.vitesse_simulation_factor)

            if hasattr(agent, "chemin") and len(agent.chemin) == 0:
                agent.etat = "Occupé"
                sym = "M"
                if isinstance(agent.destination, dict): sym = agent.destination.get("symbole", "M")

                if sym == "T":
                    agent.temps_activite = random.randint(50, 100)
                elif sym == "L":
                    agent.temps_activite = random.randint(30, 60)
                else:
                    agent.temps_activite = random.randint(20, 40)

                # Prise de place (Accès concurrentiel, nécessite un lock)
                if isinstance(agent.destination, dict):
                    for p in agent.destination["places"]:
                        if abs(agent.x - p["x"]) < 0.6 and abs(agent.y - p["y"]) < 0.6:
                            with agent.ville.lock:
                                if agent not in p["occupant"]: p["occupant"].append(agent)
                            break

        mise_a_jour(agent)

        # Régulation par pause simple (0.05 seconde)
        time.sleep(0.05)