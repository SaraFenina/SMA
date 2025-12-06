
import random
import time
from deplacerast import deplacer


def generer_nom_unique(noms):
    while True:
        s = chr(random.randint(65, 90)) + str(random.randint(0, 9))
        if s not in noms:
            noms.add(s)
            return s


# --- LISTE DES INTERDITS (Propriété privée) ---
def get_obstacles_stricts(agent):
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
    if agent.etat == "Occupé":
        if agent.temps_activite > 0: return

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
    if agent.etat != "Occupé":
        agent.energie -= 0.1
        agent.stress += 0.05
    else:
        agent.temps_activite -= 1
        sym = "M"
        if isinstance(agent.destination, dict): sym = agent.destination.get("symbole", "M")

        if sym == "T":
            agent.argent += 1; agent.stress += 0.1
        elif sym == "L":
            agent.argent -= 0.5; agent.stress -= 0.5
        elif sym == "P":
            agent.stress -= 0.2
        else:
            agent.energie += 0.5; agent.stress -= 0.1

    agent.energie = max(0, min(100, agent.energie))
    agent.stress = max(0, min(100, agent.stress))

    if agent.energie <= 0 or agent.stress >= 100:
        agent.vivant = False
        agent.etat = "Mort"


def cycle(agent):
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

                if isinstance(agent.destination, dict):
                    for p in agent.destination["places"]:
                        if abs(agent.x - p["x"]) < 0.6 and abs(agent.y - p["y"]) < 0.6:
                            with agent.ville.lock:
                                if agent not in p["occupant"]: p["occupant"].append(agent)
                            break

        mise_a_jour(agent)
        time.sleep(0.05)