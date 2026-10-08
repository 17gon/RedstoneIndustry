package net.craftoriya.redstoneIndustry.tech

class TechTree(nodes: List<TechNode>) {
    val byId: Map<String, TechNode> = nodes.associateBy { it.id }
    val dependents: Map<String, List<String>>
    val categories: Map<String, List<TechNode>>

    init {
        nodes.forEach { node ->
            node.prerequisites.forEach { require(it in byId) { "Tech ${node.id}: unknown prerequisite $it" } }
        }

        val depth = HashMap<String, Int>()
        fun depthOf(id: String, path: Set<String>): Int {
            require(id !in path) { "Tech cycle at $id" }
            return depth.getOrPut(id) {
                (byId.getValue(id).prerequisites.maxOfOrNull { depthOf(it, path + id) } ?: -1) + 1
            }
        }
        nodes.forEach { depthOf(it.id, emptySet()) }

        dependents = nodes
            .flatMap { node -> node.prerequisites.map { it to node.id } }
            .groupBy({ it.first }, { it.second })

        categories = nodes
            .sortedWith(compareBy({ depth.getValue(it.id) }, { it.id }))
            .groupBy { it.category }
    }
}