import Foundation
import Network
import XCTest

/// Our iOS bridge. Run it as a UI test and it serves the agent's screen protocol on port 8123.
/// See native/ios/README.md.
final class AgentRunner: XCTestCase {
    private var app = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
    private let queue = DispatchQueue.main

    func testServe() throws {
        continueAfterFailure = true
        let listener = try NWListener(using: .tcp, on: 8123)
        listener.newConnectionHandler = { [weak self] connection in
            connection.start(queue: .main)
            self?.receive(connection)
        }
        listener.start(queue: .main)
        RunLoop.main.run(until: .distantFuture)
    }

    private func receive(_ connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1_048_576) { [weak self] data, _, _, _ in
            guard let self, let data, let request = String(data: data, encoding: .utf8) else {
                connection.cancel()
                return
            }
            let response = self.handle(request)
            let header = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: \(response.utf8.count)\r\nConnection: close\r\n\r\n"
            connection.send(content: (header + response).data(using: .utf8), completion: .contentProcessed { _ in connection.cancel() })
        }
    }

    private func handle(_ request: String) -> String {
        let firstLine = request.components(separatedBy: "\r\n").first ?? ""
        let parts = firstLine.split(separator: " ")
        guard parts.count >= 2 else { return "{}" }
        let path = String(parts[1])
        let bodyText = request.components(separatedBy: "\r\n\r\n").dropFirst().joined(separator: "\r\n\r\n")
        let body = (try? JSONSerialization.jsonObject(with: Data(bodyText.utf8))) as? [String: Any] ?? [:]

        switch path {
        case "/launch":
            app = XCUIApplication(bundleIdentifier: body["bundleId"] as? String ?? "")
            app.activate()
        case "/open":
            app = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
            app.activate()
            if let url = URL(string: body["url"] as? String ?? "") {
                app.open(url)
            }
        case "/tap":
            point(body["x"], body["y"]).tap()
        case "/type":
            app.typeText(body["text"] as? String ?? "")
        case "/swipe":
            point(body["fromX"], body["fromY"]).press(forDuration: 0.05, thenDragTo: point(body["toX"], body["toY"]))
        case "/screen":
            return screen()
        default:
            return "{\"error\":\"unknown path\"}"
        }
        return "{\"done\":true}"
    }

    private func point(_ x: Any?, _ y: Any?) -> XCUICoordinate {
        let origin = app.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
        return origin.withOffset(CGVector(dx: x as? Double ?? 0, dy: y as? Double ?? 0))
    }

    private func screen() -> String {
        var elements: [[String: Any]] = []
        for element in app.descendants(matching: .any).allElementsBoundByIndex where element.exists {
            let name = element.label.isEmpty ? element.identifier : element.label
            if name.isEmpty { continue }
            elements.append([
                "role": role(element.elementType),
                "name": name,
                "value": element.value as? String ?? "",
                "x": Int(element.frame.minX),
                "y": Int(element.frame.minY),
                "width": Int(element.frame.width),
                "height": Int(element.frame.height),
            ])
        }
        let data = (try? JSONSerialization.data(withJSONObject: ["elements": elements])) ?? Data("{}".utf8)
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    private func role(_ type: XCUIElement.ElementType) -> String {
        switch type {
        case .button: return "button"
        case .link: return "link"
        case .textField, .secureTextField, .textView, .searchField: return "textbox"
        case .switch: return "switch"
        case .image: return "img"
        case .staticText: return "text"
        default: return "other"
        }
    }
}
