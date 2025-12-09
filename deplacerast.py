"""
    Ecrit par Rakoto Ambinintsoa Prisca
"""

import heapq
import math
import random
import time

# --- CONSTANTES PHYSIQUES ---
FORCE_ATTRACTION = 3.0       # Force pour atteindre la cible (waypoint A*)
FORCE_REPULSION_AGENT = 6.0  # Force de répulsion entre agents (pour éviter les collisions)
FORCE_REPULSION_MUR = 30.0   # Force pour empêcher l'agent de sortir des bords de la grille
DIST_ARRET = 0.5             # Distance à la cible en dessous de laquelle l'agent s'arrête
RAYON_FOV = 4.0              # Rayon de détection des autres agents (Field of View)
MAX_VITESSE = 1.0            # Vitesse maximale absolue de l'agent
DAMPING_FACTOR = 0.92        # Facteur de lissage/amortissement (réduit la vitesse pour la fluidité)


# ===========================================================
# Génération de Lieux
# ===========================================================

# Tente de générer un lieu (Travail, Parc, Loisir) de manière rectangulaire et contiguë.
# Assure que le lieu généré ne chevauche pas les coordonnées déjà occupées (maisons ou autres lieux).
# Paramètres :
#   - largeur_ville (int), hauteur_ville (int) : Dimensions de la grille.
#   - symbole (str) : Symbole du lieu ('T', 'P', 'L').
#   - capacite (int) : Nombre de places (coordonnées) que doit contenir le lieu.
#   - coordonnees_occupees_set (set) : Set des coordonnées déjà prises.
# Retourne :
#   - dict : Le lieu généré (avec places et symbole) ou None si impossible après 500 tentatives.
def generer_lieu(largeur_ville, hauteur_ville, symbole, capacite, coordonnees_occupees_set):
    nb_places = capacite
    # Marges pour ne pas coller aux bords absolus
    min_x, max_x = 1, largeur_ville - 2
    min_y, max_y = 1, hauteur_ville - 2

    for _ in range(500):
        # Calcule la taille approximative du rectangle
        taille_x = min(4, max(1, int(nb_places ** 0.5) + 1))
        taille_y = int(nb_places / taille_x) + 1

        if max_x - taille_x < min_x or max_y - taille_y < min_y: return None

        start_x = random.randint(min_x, max_x - taille_x)
        start_y = random.randint(min_y, max_y - taille_y)

        places = []
        overlap = False
        # Vérifie et collecte les coordonnées du lieu
        for dx in range(taille_x):
            for dy in range(taille_y):
                if len(places) >= nb_places: break
                px, py = start_x + dx, start_y + dy
                if (px, py) in coordonnees_occupees_set: overlap = True; break
                places.append((px, py))
            if overlap: break

        if not overlap and len(places) >= nb_places:
            places = places[:nb_places]
            for p in places: coordonnees_occupees_set.add(p)
            # Retourne le lieu formaté pour la simulation
            return {"places": [{"x": p[0], "y": p[1], "occupant": []} for p in places], "symbole": symbole}
    return None


# ===========================================================
# A* Pathfinding (Evite la propriété privée)
# ===========================================================

# Implémentation de l'algorithme A* pour trouver le chemin le plus court sur la grille.
# Évite les cases définies comme des obstacles stricts (propriété privée).
# Paramètres :
#   - start (tuple), goal (tuple) : Coordonnées (x, y) de départ et d'arrivée.
#   - largeur (int), hauteur (int) : Dimensions de la grille.
#   - obstacles_stricts (set) : Coordonnées (x, y) que l'agent ne peut pas traverser.
# Retourne :
#   - list : La liste des coordonnées (waypoints) du chemin optimal.
def _astar_grid(start, goal, largeur, hauteur, obstacles_stricts):
    dirs = [(1, 0), (-1, 0), (0, 1), (0, -1)]

    # Heuristique de Manhattan
    def h(a, b):
        return abs(a[0] - b[0]) + abs(a[1] - b[1])

    open_heap = [(0, 0, start)] # (priorité, gscore, nœud)
    came_from = {}
    gscore = {start: 0}
    visited = set()

    t_start = time.time()

    while open_heap:
        if time.time() - t_start > 0.05: return [] # Limite de temps pour ne pas bloquer le thread

        _, current_g, current = heapq.heappop(open_heap)

        if current == goal:
            # Reconstruit le chemin
            path = []
            while current in came_from:
                path.append(current)
                current = came_from[current]
            return path[::-1]

        if current in visited: continue
        visited.add(current)

        for dx, dy in dirs:
            nx, ny = current[0] + dx, current[1] + dy
            if 0 <= nx < largeur and 0 <= ny < hauteur:
                # Évite les obstacles, sauf si l'obstacle est le but lui-même
                if (nx, ny) in obstacles_stricts and (nx, ny) != goal:
                    continue

                tentative_g = current_g + 1
                if tentative_g < gscore.get((nx, ny), float('inf')):
                    came_from[(nx, ny)] = current
                    gscore[(nx, ny)] = tentative_g
                    priority = tentative_g + h((nx, ny), goal)
                    heapq.heappush(open_heap, (priority, tentative_g, (nx, ny)))
    return []


# ===========================================================
# Physique (Steering Behaviors)
# ===========================================================

# Calcule les forces totales agissant sur l'agent (Attraction vers cible, Répulsion agents, Répulsion bords).
# Paramètres :
#   - agent (Agent) : L'objet Agent.
#   - target_x (float), target_y (float) : Coordonnées du waypoint local (ou cible finale).
#   - dist_finale (float) : Distance à la destination finale (utilisée pour le freinage).
# Retourne :
#   - tuple (fx, fy) : Les forces vectorielles totales (en x et y).
def calculer_forces(agent, target_x, target_y, dist_finale):
    fx, fy = 0.0, 0.0

    # 1. Attraction vers la cible (Waypoint A*)
    dx = target_x - agent.x
    dy = target_y - agent.y
    dist = math.hypot(dx, dy)
    if dist > 0:
        # Freinage progressif à l'approche de la destination finale
        speed = FORCE_ATTRACTION
        if dist_finale < 2.0: speed *= (dist_finale / 2.0)
        fx += (dx / dist) * speed
        fy += (dy / dist) * speed

    # 2. Répulsion Agents (Steering Behavior: Separation)
    # Ignoré si l'agent est très proche de son but final
    if dist_finale > 1.0:
        for a in agent.ville.agents:
            if a is agent: continue
            vx = agent.x - a.x
            vy = agent.y - a.y
            d = math.hypot(vx, vy)
            if 0 < d < RAYON_FOV: # Si l'agent est dans le champ de vision
                # Force inversement proportionnelle au carré de la distance (forte à courte distance)
                force = FORCE_REPULSION_AGENT / (d * d)
                fx += (vx / d) * force
                fy += (vy / d) * force

    # 3. Répulsion Bords (évitement de limites)
    marge = 1.0
    w, h = agent.ville.largeur, agent.ville.hauteur
    if agent.x < marge: fx += (marge - agent.x) * FORCE_REPULSION_MUR
    if agent.x > w - marge: fx -= (agent.x - (w - marge)) * FORCE_REPULSION_MUR
    if agent.y < marge: fy += (marge - agent.y) * FORCE_REPULSION_MUR
    if agent.y > h - marge: fy -= (agent.y - (h - marge)) * FORCE_REPULSION_MUR

    return fx, fy


# ===========================================================
# Déplacement Principal
# ===========================================================

# Fonction principale de déplacement d'un agent utilisant A* pour le chemin global et les Steering Behaviors pour le mouvement local.
# Paramètres :
#   - agent (Agent) : L'objet Agent à déplacer.
#   - obstacles_stricts (set) : Coordonnées (x, y) à éviter.
#   - vitesse_simu (float) : Facteur de vitesse global de la simulation.
# Retourne :
#   - tuple (x, y) : Nouvelle position de l'agent.
def deplacer(agent, obstacles_stricts, vitesse_simu=1.0):
    if not agent.destination: return (agent.x, agent.y)

    # A. Identifier Cible Exacte (Place la plus proche ou maison)
    tx, ty = 0.0, 0.0
    if isinstance(agent.destination, dict):  # Lieu (Travail, Loisir, Parc)
        best_p = agent.destination["places"][0]
        min_d = float('inf')
        for p in agent.destination["places"]:
            # Cible une place libre ou celle qu'il occupe déjà (si recalcule)
            if not p["occupant"] or agent in p["occupant"]:
                d = math.hypot(agent.x - p["x"], agent.y - p["y"])
                if d < min_d: min_d = d; best_p = p
        tx, ty = float(best_p["x"]), float(best_p["y"])
    else:  # Tuple (Maison)
        tx, ty = float(agent.destination[0]), float(agent.destination[1])

    dist_finale = math.hypot(tx - agent.x, ty - agent.y)

    # B. Arrivée (Snap) - Si très proche de la cible, on s'arrête
    if dist_finale < DIST_ARRET:
        agent.x, agent.y = tx, ty
        agent.chemin = []
        agent.vx, agent.vy = 0.0, 0.0  # Stop net
        return (agent.x, agent.y)

    # C. Calcul A* (Chemin) - Déclenchement du recalcule du chemin A* si nécessaire
    start_n = (int(round(agent.x)), int(round(agent.y)))
    goal_n = (int(tx), int(ty))

    recalc = False
    if not agent.chemin:
        recalc = True
    elif len(agent.chemin) > 0:
        # Recalcule si l'agent s'éloigne du chemin prévu (par exemple, à cause d'une collision dynamique)
        nxt = agent.chemin[0]
        if math.hypot(agent.x - nxt[0], agent.y - nxt[1]) > 2.0: recalc = True

    if recalc:
        agent.chemin = _astar_grid(start_n, goal_n, agent.ville.largeur, agent.ville.hauteur, obstacles_stricts)

    # D. Cible Locale (Waypoint A*) - Le point précis que l'agent vise actuellement
    local_x, local_y = tx, ty
    if agent.chemin:
        # Si le prochain waypoint est atteint, on le retire du chemin
        nxt = agent.chemin[0]
        if math.hypot(nxt[0] - agent.x, nxt[1] - agent.y) < 0.8:
            agent.chemin.pop(0)
            if agent.chemin: nxt = agent.chemin[0]
        local_x, local_y = float(nxt[0]), float(nxt[1])

    # E. Application Forces & Amortissement (Mouvement Boids/Steering)
    fx, fy = calculer_forces(agent, local_x, local_y, dist_finale)

    dt = 0.1 * vitesse_simu # Pas de temps ajusté par le facteur de vitesse de la simulation

    # 1. Mise à jour de la vitesse (Intégration d'Euler simple)
    agent.vx += fx * dt
    agent.vy += fy * dt

    # 2. Amortissement (Damping)
    agent.vx *= DAMPING_FACTOR
    agent.vy *= DAMPING_FACTOR

    # 3. Cap de vitesse (Clamping)
    norm = math.hypot(agent.vx, agent.vy)
    if norm > MAX_VITESSE:
        agent.vx = (agent.vx / norm) * MAX_VITESSE
        agent.vy = (agent.vy / norm) * MAX_VITESSE

    # 4. Mise à jour de la position
    agent.x += agent.vx
    agent.y += agent.vy

    # Orientation (angle de vue)
    if norm > 0.01:
        agent.angle_vue = math.atan2(agent.vy, agent.vx)

    # Clamp final (empêche l'agent de dépasser les limites physiques de la ville)
    agent.x = max(0.1, min(agent.ville.largeur - 1.1, agent.x))
    agent.y = max(0.1, min(agent.ville.hauteur - 1.1, agent.y))

    return (agent.x, agent.y)