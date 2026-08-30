#include <netdb.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

int main(void) {
    struct addrinfo hints = {0};
    struct addrinfo *result = NULL;
    int socket_fd = -1;
    char response[64] = {0};
    const char request[] = "GET / HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n";

    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo("127.0.0.1", "8080", &hints, &result) != 0) {
        return 1;
    }
    for (struct addrinfo *current = result; current != NULL; current = current->ai_next) {
        socket_fd = socket(current->ai_family, current->ai_socktype, current->ai_protocol);
        if (socket_fd >= 0 && connect(socket_fd, current->ai_addr, current->ai_addrlen) == 0) {
            break;
        }
        if (socket_fd >= 0) {
            close(socket_fd);
            socket_fd = -1;
        }
    }
    freeaddrinfo(result);
    if (socket_fd < 0 || send(socket_fd, request, sizeof(request) - 1, 0) < 0) {
        if (socket_fd >= 0) {
            close(socket_fd);
        }
        return 1;
    }
    ssize_t bytes_read = recv(socket_fd, response, sizeof(response) - 1, 0);
    close(socket_fd);
    return bytes_read >= 12 && response[9] >= '2' && response[9] <= '3' ? 0 : 1;
}
