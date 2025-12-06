
import heapq
import math
import random
import time

# --- CONSTANTES PHYSIQUES ---
FORCE_ATTRACTION = 3.0
FORCE_REPULSION_AGENT = 8.0
FORCE_REPULSION_MUR = 50.0
DIST_ARRET = 0.5
RAYON_FOV = 4.0
MAX_VITESSE = 1.0
DAMPING_FACTOR = 0.85  # NOUVEAU: Facteur de lissage (entre 0.8 et 0.95)


# ===========================================================
# Génération de Lieux (Respecte les dimensions données)
# ===========================================================
def generer_lieu(largeur_ville, hauteur_ville, symbole, capacite, coordonnees_occupees_set):
    nb_places = capacite
    # Marges pour ne pas coller aux bords absolus
    min_x, max_x = 1, largeur_ville - 2
    min_y, max_y = 1, hauteur_ville - 2

    for _ in range(500):
        taille_x = min(4, max(1, int(nb_places ** 0.5) + 1))
        taille_y = int(nb_places / taille_x) + 1

        if max_x - taille_x < min_x or max_y - taille_y < min_y: return None

        start_x = random.randint(min_x, max_x - taille_x)
        start_y = random.randint(min_y, max_y - taille_y)

        places = []
        overlap = False
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
            return {"places": [{"x": p[0], "y": p[1], "occupant": []} for p in places], "symbole": symbole}
    return None


# ===========================================================
# A* Pathfinding (Evite la propriété privée)
# ===========================================================
def _astar_grid(start, goal, largeur, hauteur, obstacles_stricts):
    dirs = [(1, 0), (-1, 0), (0, 1), (0, -1)]

    def h(a, b):
        return abs(a[0] - b[0]) + abs(a[1] - b[1])

    open_heap = [(0, 0, start)]
    came_from = {}
    gscore = {start: 0}
    visited = set()

    t_start = time.time()

    while open_heap:
        if time.time() - t_start > 0.05: return []

        _, current_g, current = heapq.heappop(open_heap)

        if current == goal:
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
def calculer_forces(agent, target_x, target_y, dist_finale):
    fx, fy = 0.0, 0.0

    # 1. Attraction vers la cible
    dx = target_x - agent.x
    dy = target_y - agent.y
    dist = math.hypot(dx, dy)
    if dist > 0:
        # Freinage à l'approche
        speed = FORCE_ATTRACTION
        if dist_finale < 2.0: speed *= (dist_finale / 2.0)
        fx += (dx / dist) * speed
        fy += (dy / dist) * speed

    # 2. Répulsion Agents
    if dist_finale > 1.0:
        for a in agent.ville.agents:
            if a is agent: continue
            vx = agent.x - a.x
            vy = agent.y - a.y
            d = math.hypot(vx, vy)
            if 0 < d < RAYON_FOV:
                force = FORCE_REPULSION_AGENT / (d * d)
                fx += (vx / d) * force
                fy += (vy / d) * force

    # 3. Répulsion Bords
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
def deplacer(agent, obstacles_stricts, vitesse_simu=1.0):
    if not agent.destination: return (agent.x, agent.y)

    # A. Identifier Cible Exacte
    tx, ty = 0.0, 0.0
    if isinstance(agent.destination, dict):  # Lieu
        best_p = agent.destination["places"][0]
        min_d = float('inf')
        for p in agent.destination["places"]:
            if not p["occupant"] or agent in p["occupant"]:
                d = math.hypot(agent.x - p["x"], agent.y - p["y"])
                if d < min_d: min_d = d; best_p = p
        tx, ty = float(best_p["x"]), float(best_p["y"])
    else:  # Tuple (Maison)
        tx, ty = float(agent.destination[0]), float(agent.destination[1])

    dist_finale = math.hypot(tx - agent.x, ty - agent.y)

    # B. Arrivée (Snap)
    if dist_finale < DIST_ARRET:
        agent.x, agent.y = tx, ty
        agent.chemin = []
        agent.vx, agent.vy = 0.0, 0.0  # Stop net
        return (agent.x, agent.y)

    # C. Calcul A* (Chemin)
    start_n = (int(round(agent.x)), int(round(agent.y)))
    goal_n = (int(tx), int(ty))

    recalc = False
    if not agent.chemin:
        recalc = True
    elif len(agent.chemin) > 0:
        # Si on s'éloigne trop du chemin (collision ou déviation), on recalcule
        nxt = agent.chemin[0]
        if math.hypot(agent.x - nxt[0], agent.y - nxt[1]) > 2.0: recalc = True

    if recalc:
        agent.chemin = _astar_grid(start_n, goal_n, agent.ville.largeur, agent.ville.hauteur, obstacles_stricts)

    # D. Cible Locale (Waypoint A*)
    local_x, local_y = tx, ty
    if agent.chemin:
        nxt = agent.chemin[0]
        if math.hypot(nxt[0] - agent.x, nxt[1] - agent.y) < 0.8:
            agent.chemin.pop(0)
            if agent.chemin: nxt = agent.chemin[0]
        local_x, local_y = float(nxt[0]), float(nxt[1])

    # E. Application Forces & Amortissement
    fx, fy = calculer_forces(agent, local_x, local_y, dist_finale)

    dt = 0.1 * vitesse_simu

    # 1. Mise à jour de la vitesse (Accélération = Force / Masse (M=1))
    agent.vx += fx * dt
    agent.vy += fy * dt

    # 2. Amortissement (Réduit la vitesse pour lisser et éviter l'oscillation)
    agent.vx *= DAMPING_FACTOR
    agent.vy *= DAMPING_FACTOR

    # 3. Cap de vitesse
    norm = math.hypot(agent.vx, agent.vy)
    if norm > MAX_VITESSE:
        agent.vx = (agent.vx / norm) * MAX_VITESSE
        agent.vy = (agent.vy / norm) * MAX_VITESSE

    # 4. Mise à jour de la position
    agent.x += agent.vx
    agent.y += agent.vy

    # Orientation
    if norm > 0.01:
        agent.angle_vue = math.atan2(agent.vy, agent.vx)

    # Clamp final
    agent.x = max(0.1, min(agent.ville.largeur - 1.1, agent.x))
    agent.y = max(0.1, min(agent.ville.hauteur - 1.1, agent.y))

    return (agent.x, agent.y)