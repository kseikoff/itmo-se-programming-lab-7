package databaseModule.repository;

import model.Coordinates;
import model.Location;
import model.Person;
import utils.MappingUtils;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.sql.*;
import java.util.Properties;

public class PersonRepositoryImpl implements PersonRepository, AccessControlRepository, Closeable {
    private final Connection connection;
    private final CoordinatesRepositoryImpl coordinatesRepository;
    private final LocationRepositoryImpl locationRepository;

    public PersonRepositoryImpl() throws IOException, SQLException {
        Properties info = new Properties();
        try (InputStream configStream = getClass().getResourceAsStream("/db.cfg")) {
            info.load(configStream);
        } catch (IOException e) {
            throw new IOException("Error loading db.cfg file", e);
        }

        try {
            connection = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/studs", info);
        } catch (SQLException e) {
            throw new SQLException("Error connecting to the database", e);
        }

        coordinatesRepository = new CoordinatesRepositoryImpl(this.connection);
        locationRepository = new LocationRepositoryImpl(this.connection);
    }

    public PersonRepositoryImpl(Connection connection) {
        this.connection = connection;

        coordinatesRepository = new CoordinatesRepositoryImpl(this.connection);
        locationRepository = new LocationRepositoryImpl(this.connection);
    }

    @Override
    public boolean insert(Person person, int ownerId) throws SQLException {
        String insertQuery = "INSERT INTO person " +
                "(name, coordinates_id, creation_date, height, " +
                "birthday, passport_id, hair_color, location_id, owner_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        Coordinates coordinates = person.getCoordinates();
        Location location = person.getLocation();
        boolean nullLocation = location == null;

        coordinatesRepository.insert(coordinates);
        if (!nullLocation) {
            locationRepository.insert(location);   
        }

        int coordinatesId = coordinatesRepository.getElementId(coordinates);
        int locationId = 0;
        if (!nullLocation) {
            locationId = locationRepository.getElementId(location);
        }

        int affectedRows;
        try (PreparedStatement preparedStatement = connection.prepareStatement(insertQuery, Statement.RETURN_GENERATED_KEYS)) {
            preparedStatement.setString(1, person.getName());
            preparedStatement.setInt(2, coordinatesId);
            preparedStatement.setTimestamp(3, new Timestamp(person.getCreationDate().getTime()));
            preparedStatement.setInt(4, person.getHeight());
            preparedStatement.setDate(5, new java.sql.Date(person.getBirthday().getTime()));
            preparedStatement.setString(6, person.getPassportId());
            preparedStatement.setString(7, (person.getHairColor() != null) ? person.getHairColor().getLabel() : null);
            if (!nullLocation) {
                preparedStatement.setInt(8, locationId);
            } else {
                preparedStatement.setNull(8, Types.INTEGER);
            }
            preparedStatement.setInt(9, ownerId);

            affectedRows = preparedStatement.executeUpdate();

            ResultSet generatedKeys = preparedStatement.getGeneratedKeys();
            if (generatedKeys.next()) {
                person.setId(generatedKeys.getInt(1));
            }
        } catch (SQLException e) {
            throw new SQLException("Error adding person to the database", e);
        }

        return affectedRows > 0;
    }

    @Override
    public Person read(int id) throws SQLException {
        String selectQuery = "SELECT * FROM person WHERE id = ?";

        try (PreparedStatement preparedStatement = connection.prepareStatement(selectQuery)) {
            preparedStatement.setInt(1, id);

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return MappingUtils.mapResultSetToPerson(resultSet);
                } else {
                    return null;
                }
            }
        } catch (SQLException e) {
            throw new SQLException("Error reading person from the database", e);
        }
    }

    @Override
    public boolean remove(int id) throws SQLException {
        String deleteQuery = "DELETE FROM person WHERE id = ?";

        int affectedRows;
        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteQuery)) {
            preparedStatement.setInt(1, id);

            affectedRows = preparedStatement.executeUpdate();
        } catch (SQLException e) {
            throw new SQLException("Error removing person from the database", e);
        }

        return affectedRows > 0;
    }

    @Override
    public boolean update(Person person, int id) throws SQLException {
        String getIdsQuery = "SELECT coordinates_id, location_id FROM person WHERE id = ?";

        try (PreparedStatement getIdStatement = connection.prepareStatement(getIdsQuery)) {
            getIdStatement.setInt(1, id);

            try (ResultSet resultSet = getIdStatement.executeQuery()) {
                if (resultSet.next()) {
                    Coordinates coordinates = person.getCoordinates();
                    Location location = person.getLocation();
                    boolean nullLocation = location == null;

                    int coordinatesId = resultSet.getInt("coordinates_id");
                    int locationId = resultSet.getInt("location_id");

                    if (resultSet.wasNull() && !nullLocation) {
                        locationRepository.insert(location);
                        locationId = locationRepository.getElementId(location);
                    } else if (!nullLocation) {
                        locationRepository.update(location, locationId);
                    }

                    coordinatesRepository.update(coordinates, coordinatesId);

                    String updateQuery = "UPDATE person " +
                            "SET name = ?, coordinates_id = ?, creation_date = ?, " +
                            "height = ?, birthday = ?, passport_id = ?, " +
                            "hair_color = ?, location_id = ? " +
                            "WHERE id = ?";

                    int affectedRows;
                    try (PreparedStatement preparedStatement = connection.prepareStatement(updateQuery)) {
                        preparedStatement.setString(1, person.getName());
                        preparedStatement.setInt(2, coordinatesId);
                        preparedStatement.setTimestamp(3, new Timestamp(person.getCreationDate().getTime()));
                        preparedStatement.setInt(4, person.getHeight());
                        preparedStatement.setDate(5, new java.sql.Date(person.getBirthday().getTime()));
                        preparedStatement.setString(6, person.getPassportId());
                        preparedStatement.setString(7, (person.getHairColor() != null) ? person.getHairColor().getLabel() : null);
                        if (!nullLocation) {
                            preparedStatement.setInt(8, locationId);
                        } else {
                            preparedStatement.setNull(8, Types.INTEGER);
                        }
                        preparedStatement.setInt(9, id);

                        affectedRows = preparedStatement.executeUpdate();
                    } catch (SQLException e) {
                        throw new SQLException("Error updating person in the database", e);
                    }

                    return affectedRows > 0;
                } else {
                    throw new SQLException("Person not found");
                }
            }
        }
    }

    @Override
    public boolean checkAccess(int elementId, int ownerId) throws SQLException {
        String getIdsQuery = "SELECT owner_id FROM person WHERE id = ?";

        try (PreparedStatement getIdStatement = connection.prepareStatement(getIdsQuery)) {
            getIdStatement.setInt(1, elementId);

            try (ResultSet resultSet = getIdStatement.executeQuery()) {
                if (resultSet.next()) {
                    int personOwnerId = resultSet.getInt("owner_id");
                    return personOwnerId == ownerId;
                } else {
                    return false;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IOException(e);
        }
    }
}
