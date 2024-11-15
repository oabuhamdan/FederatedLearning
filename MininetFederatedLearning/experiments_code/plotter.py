import matplotlib.pyplot as plt
import networkx as nx


class MininetPlotter:
    def __init__(self, net):
        self.net = net
        self.G = nx.Graph()
        self.graph_built = False

    def build_graph(self):
        """Build the graph object using Mininet nodes and links"""
        # Add nodes (hosts and switches)
        for node in self.net.hosts + self.net.switches:
            self.G.add_node(node.name)

        # Add links (edges between hosts and switches)
        for link in self.net.links:
            node1, node2 = link.intf1.node, link.intf2.node
            self.G.add_edge(node1.name, node2.name)
        self.graph_built = True

    def plot(self):
        if not self.graph_built:
            self.build_graph()
        """Plot the network topology"""
        # Create position layout using spring layout
        plt.figure(figsize=(15, 10))  # Adjust this as needed for your topology
        pos = nx.spring_layout(self.G, seed=42)

        # Define node colors and shapes
        node_colors = []
        node_shapes = []

        # Assign colors and shapes based on node type (host or switch)
        for node in self.G.nodes:
            if node[:2] in ["cs", "is", "as", "es"]:  # Switches (start with 's')
                node_colors.append('orange')  # Switch color
                node_shapes.append('s')  # Switch shape (square)
            else:  # Hosts
                if node == 'flserver':  # Special color for fl_server
                    node_colors.append('red')
                else:
                    node_colors.append('lightblue')  # Default host color
                node_shapes.append('o')  # Host shape (circle)

        # Plot nodes with specific shapes and colors
        for shape, color, node in zip(node_shapes, node_colors, self.G.nodes):
            if shape == 's':  # Switch (square)
                nx.draw_networkx_nodes(self.G, pos, nodelist=[node], node_size=100, node_shape='s', node_color=color)
            else:  # Host (circle)
                nx.draw_networkx_nodes(self.G, pos, nodelist=[node], node_size=100, node_shape='o', node_color=color)

        # Plot edges (links between nodes)
        nx.draw_networkx_edges(self.G, pos, edgelist=self.G.edges(), width=2, alpha=0.5, edge_color='gray')

        # Plot labels for nodes
        nx.draw_networkx_labels(self.G, pos, font_size=10, font_weight='bold', font_color='black')

        # Set plot title
        plt.title("Mininet Topology")

        # Hide axes for better visualization
        plt.axis('off')

        # Show the plot
        plt.show(block=True)
