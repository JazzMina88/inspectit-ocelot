import React from 'react';
import {Button} from 'primereact/button';
import {Dialog} from 'primereact/dialog';
import Dropzone from 'react-dropzone';
import {configurationActions} from '../../../../redux/ducks/configuration';
import PropTypes from 'prop-types';
import {isSelectionDirectory} from '../../../../redux/ducks/configuration/selectors';
import {connect} from 'react-redux';

class UploadDialog extends React.Component {
  constructor() {
    super();
    this.handleClick = this.handleClick.bind(this);
    this.onDrop = (files) => {
      this.setState({files});
    };
    this.state = {
      files: [],
    };
  }

  render() {
    console.log(this.props);

    const files = this.state.files.map((file) => (
      <li key={file.name}>
        {file.name} - {file.size} bytes
      </li>
    ));

    return (
      <Dialog
        header={'Upload file/directory'}
        modal={true}
        visible={this.props.visible}
        onHide={this.props.onHide}
        footer={
          <div>
            <Button label="Upload" onClick={this.handleClick}/>
            <Button label="Cancel" className="p-button-secondary" onClick={this.props.onHide}/>
          </div>
        }
      >
        <Dropzone onDrop={this.onDrop}>
          {({getRootProps, getInputProps}) => (
            <section className="container">
              <div {...getRootProps({className: 'dropzone'})}>
                <input {...getInputProps()} />
                <p>Drag and drop some files here, or click to select files</p>
              </div>
              <aside>
                <h4>Files</h4>
                <ul>{files}</ul>
              </aside>
            </section>
          )}
        </Dropzone>
      </Dialog>
    );
  }

  handleClick() {  
    this.state.files.forEach((file) => {
      const fileName = this.props.selection + '/' + file.name;
      const content = "text";
      this.props.writeFile(fileName, content, true, true);
    });
    this.props.onHide();
  }
}

function mapStateToProps(state) {
  const {selection, files} = state.configuration;

  const isDirectory = isSelectionDirectory(state);

  return {
    isDirectory,
    files,
    selection,
  };
}

const mapDispatchToProps = {
  writeFile: configurationActions.writeFile,
};

UploadDialog.props = {
  filePath: PropTypes.string,
  directoryMode: PropTypes.bool,
  visible: PropTypes.bool,
  onHide: PropTypes.bool,
  writeFile: PropTypes.func,
};

UploadDialog.defaultProperties = {
  visible: true,
  onHide: () => {
  },
  writeFile: () => {
  },
};

export default connect(mapStateToProps, mapDispatchToProps)(UploadDialog);
