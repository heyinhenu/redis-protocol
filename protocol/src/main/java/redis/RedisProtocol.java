package redis;

import redis.reply.BulkReply;
import redis.reply.ErrorReply;
import redis.reply.IntegerReply;
import redis.reply.MultiBulkReply;
import redis.reply.Reply;
import redis.reply.StatusReply;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/**
 * Implements the Redis Universal Protocol. Send a command, receive a command, send a reply
 * and receive a reply.
 */
public class RedisProtocol {

  private final DataInputStream is;
  private final OutputStream os;

  public RedisProtocol(Socket socket) throws IOException {
    is = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
    os = new BufferedOutputStream(socket.getOutputStream());
  }

  public static byte[] readBytes(DataInputStream is) throws IOException {
    int size = readInteger(is);
    int read;
    if (size == -1) {
      return null;
    }
    byte[] bytes = new byte[size];
    int total = 0;
    while (total < bytes.length && (read = is.read(bytes, total, bytes.length - total)) != -1) {
      total += read;
    }
    if (total < bytes.length) {
      throw new IOException("Failed to read enough bytes: " + total);
    }
    int cr = is.read();
    int lf = is.read();
    if (cr != '\r' || lf != '\n') {
      throw new IOException("Improper line ending: " + cr + ", " + lf);
    }
    return bytes;
  }

  public static int readInteger(DataInputStream is) throws IOException {
    int size = 0;
    boolean negative = false;
    boolean sign = false;
    int read;
    while ((read = is.read()) != -1) {
      if (read == '-') {
        if (sign) {
          throw new IOException("Invalid character in integer");
        } else {
          negative = true;
        }
      } else {
        int value = read - '0';
        if (value >= 0 && value < 10) {
          size *= 10;
          size += value;
        } else {
          if (read == '\r') {
            if (is.read() == '\n') {
              break;
            }
          }
          throw new IOException("Invalid character in integer");
        }
      }
      sign = true;
    }
    size = negative ? -size : size;
    return size;
  }

  public static Reply receive(DataInputStream is) throws IOException {
    int code = is.read();
    switch (code) {
      case StatusReply.MARKER: {
        return new StatusReply(is.readLine());
      }
      case ErrorReply.MARKER: {
        return new ErrorReply(is.readLine());
      }
      case IntegerReply.MARKER: {
        return new IntegerReply(Integer.parseInt(is.readLine()));
      }
      case BulkReply.MARKER: {
        return new BulkReply(readBytes(is));
      }
      case MultiBulkReply.MARKER: {
        return new MultiBulkReply(is);
      }
      default: {
        throw new IOException("Unexpected character in stream: " + code);
      }
    }
  }

  public Reply send(Command command) throws IOException {
    sendAsync(command);
    return receiveAsync();
  }

  public Reply receiveAsync() throws IOException {
    synchronized (is) {
      return receive(is);
    }
  }

  public void sendAsync(Command command) throws IOException {
    synchronized (os) {
      command.write(os);
      os.flush();
    }
  }

  public Command receive() throws IOException {
    synchronized (is) {
      return Command.read(is);
    }
  }

  public void send(Reply reply) throws IOException {
    synchronized (os) {
      reply.write(os);
      os.flush();
    }
  }

  public void write(byte[] bytes) throws IOException {
    synchronized (os) {
      os.write(bytes);
      os.flush();
    }
  }

  public void sendAsync(Object[] objects) throws IOException {
    synchronized (os) {
      Command.writeDirect(os, objects);
      os.flush();
    }
  }
}
