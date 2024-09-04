import matplotlib.pyplot as plt
import re

string = ""

# Regular expression to match the coordinates
pattern = r"\((-?\d+\.\d+),\s*(-?\d+\.\d+)\)"

# Find all matches
matches = re.findall(pattern, string)

# Convert the matches to a list of tuples with float values
coordinates = [(float(x), float(y)) for x, y in matches]

# Unzip the coordinates into two lists: x and y
x, y = zip(*coordinates)

# Create a scatter plot
plt.scatter(x, y)

# Optionally, connect the points with lines
plt.plot(x, y, linestyle='-', color='blue')

# Label the axes
plt.xlabel('X-axis')
plt.ylabel('Y-axis')

# Set a title
plt.title('Plot of Coordinates')

# Display the plot
plt.show()
